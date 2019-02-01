package com.jaytech.springbootgrpc.routeguide;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import io.grpc.testing.GrpcCleanupRule;


/**
 * @author junho.park
 */
@RunWith(JUnit4.class)
public class RouteGuideServerTest {

    /**
     * 이 룰은 등록된 채널을 테스트가 끝날 때 닫아준다
     */
    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

    private RouteGuideServer server;
    private ManagedChannel inProcessChannel;
    private Collection<Feature> features;

    @Before
    public void setUp() throws Exception {
        // 유니크한 서버 이름을 지정한다.
        String serverName = InProcessServerBuilder.generateName();
        features = new ArrayList<>();

        server = new RouteGuideServer(
                InProcessServerBuilder.forName(serverName).directExecutor(), 0, features);
        server.start();

        // 클라이언트 채널을 만들고 위에서 등록한 GrpcCleanupRul을 적용해 추후 셧다운을 시킨다.
        inProcessChannel = grpcCleanup.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void getFeature() {
        Point point = Point.newBuilder().setLongitude(1).setLatitude(1).build();
        Feature unnamedFeature = Feature.newBuilder()
                .setName("").setLocation(point).build();
        RouteGuideGrpc.RouteGuideBlockingStub stub = RouteGuideGrpc.newBlockingStub(inProcessChannel);

        // 서버에서 못 찾았다면 (보니까 내부적으로 구현은 그냥 이름이 없으면 서버에서 못찾았다고 반환한다. 어차피 예제니까)
        Feature feature = stub.getFeature(point);

        assertEquals(unnamedFeature, feature);

        // 서버에서 찾았다면
        Feature namedFeature = Feature.newBuilder()
                .setName("name").setLocation(point).build();
        features.add(namedFeature);

        feature = stub.getFeature(point);

        assertEquals(namedFeature, feature);
    }

    @Test
    public void listFeauture() throws Exception {
        // setup
        Rectangle rect = Rectangle.newBuilder()
                .setLo(Point.newBuilder().setLongitude(0).setLatitude(0).build())
                .setHi(Point.newBuilder().setLongitude(10).setLatitude(10).build())
                .build();
        Feature f1 = Feature.newBuilder()
                .setLocation(Point.newBuilder().setLongitude(-1).setLatitude(-1).build())
                .setName("f1")
                .build(); // not inside rect
        Feature f2 = Feature.newBuilder()
                .setLocation(Point.newBuilder().setLongitude(2).setLatitude(2).build())
                .setName("f2")
                .build();
        Feature f3 = Feature.newBuilder()
                .setLocation(Point.newBuilder().setLongitude(3).setLatitude(3).build())
                .setName("f3")
                .build();
        Feature f4 = Feature.newBuilder()
                .setLocation(Point.newBuilder().setLongitude(4).setLatitude(4).build())
                .build(); // unamed

        features.add(f1);
        features.add(f2);
        features.add(f3);
        features.add(f4);

        final Collection<Feature> result = new HashSet<Feature>();
        final CountDownLatch latch = new CountDownLatch(1);

        StreamObserver<Feature> responseObserver =
                new StreamObserver<Feature>() {
                    @Override
                    public void onNext(Feature value) {
                        result.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        fail();
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                };

        RouteGuideGrpc.RouteGuideStub stub = RouteGuideGrpc.newStub(inProcessChannel);

        // run
        stub.listFeatures(rect, responseObserver);
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // verify
        assertEquals(new HashSet<Feature>(Arrays.asList(f2, f3)), result);
    }

}