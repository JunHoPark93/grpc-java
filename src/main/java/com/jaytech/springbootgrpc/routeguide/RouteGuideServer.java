package com.jaytech.springbootgrpc.routeguide;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Math.*;
import static java.lang.StrictMath.atan2;
import static java.lang.StrictMath.cos;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * @author junho.park
 */
public class RouteGuideServer {
    private static final Logger logger = Logger.getLogger(RouteGuideServer.class.getName());

    private final int port;
    private final Server server;

    public RouteGuideServer(int port) throws IOException {
        this(port, RouteGuideUtil.getDefaultFeaturesFile());
    }

    /** Create a RouteGuide server listening on {@code port} using {@code featureFile} database. */
    public RouteGuideServer(int port, URL featureFile) throws IOException {
        this(ServerBuilder.forPort(port), port, RouteGuideUtil.parseFeatures(featureFile));
    }

    /** Create a RouteGuide server using serverBuilder as a base and features as data. */
    public RouteGuideServer(ServerBuilder<?> serverBuilder, int port, Collection<Feature> features) {
        this.port = port;
        server = serverBuilder.addService(new RouteGuideService(features))
                .build();
    }

    /** Start serving requests. */
    public void start() throws IOException {
        server.start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // stderr를 쓰는 이유는 로거가 JVM 셧다운 훅에 의해서 리셋 될 수 있기 때문이다.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                RouteGuideServer.this.stop();
                System.err.println("*** server shut down");
            }
        });
    }

    /** 요청을 더 이상 받지 않고 리소스를 종료한다. */
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * grpc는 데몬 쓰레드를 사용하기 때문에 메인 쓰레드의 종료를 기다려야 한다.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    public static void main(String[] args) throws Exception {
        RouteGuideServer server = new RouteGuideServer(8980);
        server.start();
        server.blockUntilShutdown();
    }

    /**
     * RouteGuideService의 구현
     */
    private static class RouteGuideService extends RouteGuideGrpc.RouteGuideImplBase {
        private final Collection<Feature> features;
        // concurrentmap은 hashmap의 멀티스레드 환경에서의 동기화 문제를 보완한다.
        private final ConcurrentMap<Point, List<RouteNote>> routeNotes =
                new ConcurrentHashMap<Point, List<RouteNote>>();

        RouteGuideService(Collection<Feature> features) {
            this.features = features;
        }

        /**
         * request를 받고 해당 location에 해당 되는 것이 없다면 unnamed한 feature를 반환한다.
         * @param request feature에 대한 요청된 location
         * @param responseObserver 요청된 Point request의 결과가 반환 되는 곳
         */
        @Override
        public void getFeature(Point request, StreamObserver<Feature> responseObserver) {
            responseObserver.onNext(checkFeature(request));
            responseObserver.onCompleted();
        }

        /**
         * Rectangle 바운딩에 해당하는 모든 features들을 받는다.
         * @param request 요청되는 rectangle
         * @param responseObserver feautre를 받는 옵저버
         */
        @Override
        public void listFeatures(Rectangle request, StreamObserver<Feature> responseObserver) {
            int left = min(request.getLo().getLongitude(), request.getHi().getLongitude());
            int right = max(request.getLo().getLongitude(), request.getHi().getLongitude());
            int top = max(request.getLo().getLatitude(), request.getHi().getLatitude());
            int bottom = min(request.getLo().getLatitude(), request.getHi().getLatitude());

            for (Feature feature : features) {
                if (!RouteGuideUtil.exists(feature)) {
                    continue;
                }

                int lat = feature.getLocation().getLatitude();
                int lon = feature.getLocation().getLongitude();
                if (lon >= left && lon <= right && lat >= bottom && lat <= top) {
                    responseObserver.onNext(feature);
                }
            }
            responseObserver.onCompleted();
        }

        /**
         * Gets a stream of points, and responds with statistics about the "trip": number of points,
         * number of known features visited, total distance traveled, and total time spent.
         *
         * @param responseObserver an observer to receive the response summary.
         * @return an observer to receive the requested route points.
         */
        @Override
        public StreamObserver<Point> recordRoute(final StreamObserver<RouteSummary> responseObserver) {
            return new StreamObserver<Point>() {
                int pointCount;
                int featureCount;
                int distance;
                Point previous;
                final long startTime = System.nanoTime();

                @Override
                public void onNext(Point point) {
                    pointCount++;
                    if (RouteGuideUtil.exists(checkFeature(point))) {
                        featureCount++;
                    }
                    // For each point after the first, add the incremental distance from the previous point to
                    // the total distance value.
                    if (previous != null) {
                        distance += calcDistance(previous, point);
                    }
                    previous = point;
                }

                @Override
                public void onError(Throwable t) {
                    logger.log(Level.WARNING, "recordRoute cancelled");
                }

                @Override
                public void onCompleted() {
                    long seconds = NANOSECONDS.toSeconds(System.nanoTime() - startTime);
                    responseObserver.onNext(RouteSummary.newBuilder().setPointCount(pointCount)
                            .setFeatureCount(featureCount).setDistance(distance)
                            .setElapsedTime((int) seconds).build());
                    responseObserver.onCompleted();
                }
            };
        }

        /**
         * Receives a stream of message/location pairs, and responds with a stream of all previous
         * messages at each of those locations.
         *
         * @param responseObserver an observer to receive the stream of previous messages.
         * @return an observer to handle requested message/location pairs.
         */
        @Override
        public StreamObserver<RouteNote> routeChat(final StreamObserver<RouteNote> responseObserver) {
            return new StreamObserver<RouteNote>() {
                @Override
                public void onNext(RouteNote note) {
                    List<RouteNote> notes = getOrCreateNotes(note.getLocation());

                    // Respond with all previous notes at this location.
                    for (RouteNote prevNote : notes.toArray(new RouteNote[0])) {
                        responseObserver.onNext(prevNote);
                    }

                    // Now add the new note to the list
                    notes.add(note);
                }

                @Override
                public void onError(Throwable t) {
                    logger.log(Level.WARNING, "routeChat cancelled");
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }

        /**
         * Get the notes list for the given location. If missing, create it.
         */
        private List<RouteNote> getOrCreateNotes(Point location) {
            List<RouteNote> notes = Collections.synchronizedList(new ArrayList<RouteNote>());
            List<RouteNote> prevNotes = routeNotes.putIfAbsent(location, notes);
            return prevNotes != null ? prevNotes : notes;
        }

        /**
         * Gets the feature at the given point.
         *
         * @param location the location to check.
         * @return The feature object at the point. Note that an empty name indicates no feature.
         */
        private Feature checkFeature(Point location) {
            for (Feature feature : features) {
                if (feature.getLocation().getLatitude() == location.getLatitude()
                        && feature.getLocation().getLongitude() == location.getLongitude()) {
                    return feature;
                }
            }

            // No feature was found, return an unnamed feature.
            return Feature.newBuilder().setName("").setLocation(location).build();
        }

        /**
         * Calculate the distance between two points using the "haversine" formula.
         * The formula is based on http://mathforum.org/library/drmath/view/51879.html.
         *
         * @param start The starting point
         * @param end The end point
         * @return The distance between the points in meters
         */
        private static int calcDistance(Point start, Point end) {
            int r = 6371000; // earth radius in meters
            double lat1 = toRadians(RouteGuideUtil.getLatitude(start));
            double lat2 = toRadians(RouteGuideUtil.getLatitude(end));
            double lon1 = toRadians(RouteGuideUtil.getLongitude(start));
            double lon2 = toRadians(RouteGuideUtil.getLongitude(end));
            double deltaLat = lat2 - lat1;
            double deltaLon = lon2 - lon1;

            double a = sin(deltaLat / 2) * sin(deltaLat / 2)
                    + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2);
            double c = 2 * atan2(sqrt(a), sqrt(1 - a));

            return (int) (r * c);
        }
    }
}
