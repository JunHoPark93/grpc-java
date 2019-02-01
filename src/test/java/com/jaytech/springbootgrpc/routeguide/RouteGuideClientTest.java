package com.jaytech.springbootgrpc.routeguide;

import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.MutableHandlerRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author junho.park
 */
@RunWith(JUnit4.class)
public class RouteGuideClientTest {
    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    private RouteGuideClient client;

    private final MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
    private final RouteGuideClient.TestHelper testHelper = mock(RouteGuideClient.TestHelper.class);

    @Before
    public void setUp() throws Exception {
        String serverName = InProcessServerBuilder.generateName();

        // 나중에 각각 테스트를 할때 service impl register를 하기 위해서 mutable 서비스 레지스트리를 사용한다.
        grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                .fallbackHandlerRegistry(serviceRegistry).directExecutor().build().start());
        client =
                new RouteGuideClient(InProcessChannelBuilder.forName(serverName).directExecutor());
        client.setTestHelper(testHelper);
    }

    @After
    public void tearDown() throws Exception {
        client.shutdown();
    }

    /**
     * 단일 요청 테스트
     */
    @Test
    public void getFeature() {
        Point requestPoint =  Point.newBuilder().setLatitude(-1).setLongitude(-1).build();
        Point responsePoint = Point.newBuilder().setLatitude(-123).setLongitude(-123).build();
        final AtomicReference<Point> pointDelivered = new AtomicReference<Point>();
        final Feature responseFeature =
                Feature.newBuilder().setName("dummyFeature").setLocation(responsePoint).build();

        // implement the fake service
        RouteGuideGrpc.RouteGuideImplBase getFeatureImpl =
                new RouteGuideGrpc.RouteGuideImplBase() {
                    @Override
                    public void getFeature(Point point, StreamObserver<Feature> responseObserver) {
                        pointDelivered.set(point);
                        responseObserver.onNext(responseFeature);
                        responseObserver.onCompleted();
                    }
                };
        serviceRegistry.addService(getFeatureImpl);

        client.getFeature(-1, -1);

        assertEquals(requestPoint, pointDelivered.get());
        verify(testHelper).onMessage(responseFeature);
        verify(testHelper, never()).onRpcError(any(Throwable.class));
    }

    @Test
    public void listFeatures() {
        final Feature responseFeature1 = Feature.newBuilder().setName("feature 1").build();
        final Feature responseFeature2 = Feature.newBuilder().setName("feature 2").build();
        final AtomicReference<Rectangle> rectangleDelivered = new AtomicReference<Rectangle>();

        // implement the fake service
        RouteGuideGrpc.RouteGuideImplBase listFeaturesImpl =
                new RouteGuideGrpc.RouteGuideImplBase() {
                    @Override
                    public void listFeatures(Rectangle rectangle, StreamObserver<Feature> responseObserver) {
                        rectangleDelivered.set(rectangle);

                        // send two response messages
                        responseObserver.onNext(responseFeature1);
                        responseObserver.onNext(responseFeature2);

                        // complete the response
                        responseObserver.onCompleted();
                    }
                };
        serviceRegistry.addService(listFeaturesImpl);

        client.listFeatures(1, 2, 3, 4);

        assertEquals(Rectangle.newBuilder()
                        .setLo(Point.newBuilder().setLatitude(1).setLongitude(2).build())
                        .setHi(Point.newBuilder().setLatitude(3).setLongitude(4).build())
                        .build(),
                rectangleDelivered.get());
        verify(testHelper).onMessage(responseFeature1);
        verify(testHelper).onMessage(responseFeature2);
        verify(testHelper, never()).onRpcError(any(Throwable.class));
    }
}