package com.example.robot_agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import geometry_msgs.PoseStamped;
import geometry_msgs.PoseWithCovarianceStamped;
import geometry_msgs.Twist;
import geometry_msgs.TwistStamped;
import nav_msgs.OccupancyGrid;
import nav_msgs.Odometry;
import sensor_msgs.BatteryState;
import sensor_msgs.CompressedImage;
import sensor_msgs.LaserScan;
import std_msgs.String_;

import us.ihmc.jros2.ROS2Node;
import us.ihmc.jros2.ROS2Publisher;
import us.ihmc.jros2.ROS2Subscription;
import us.ihmc.jros2.ROS2Topic;

public class ROS2NativeManager {
    private static final String TAG = "ROS2_NativeManager";

    public enum CmdVelType {
        TWIST,
        TWIST_STAMPED
    }

    private volatile CmdVelType currentCmdVelType = CmdVelType.TWIST;

    public interface ROS2DataListener {
        void onCompressedImageReceived(Bitmap bitmap);
        void onLaserScanReceived(float[] ranges, float angleMin, float angleInc);
        void onMapReceived(byte[] data, int width, int height, float resolution, float originX, float originY);
        void onRobotPoseReceived(float worldX, float worldY, float yaw, boolean isAmcl);
        void onBatteryStateReceived(float percentage, float voltage, boolean isCharging);
        void onCustomTopicReceived(String topic, String data);
        void onServiceResponseReceived(String serviceName, String result);
        void onStatusChanged(String status, boolean isRunning);
        void onLog(String logMessage);
        void onError(String title, String message);
    }

    private final Context context;
    private final ROS2DataListener listener;
    private WifiManager.MulticastLock multicastLock;

    private final ExecutorService nodeExecutor = Executors.newSingleThreadExecutor();

    private ROS2Node ros2Node;
    private ROS2Publisher<Twist> cmdVelPublisher;
    private ROS2Publisher<TwistStamped> cmdVelStampedPublisher;
    private ROS2Publisher<PoseStamped> goalPublisher;
    private ROS2Publisher<PoseWithCovarianceStamped> initialPosePublisher;
    private final ConcurrentHashMap<String, ROS2Publisher<String_>> stringPublisherMap = new ConcurrentHashMap<>();

    private ROS2Subscription<CompressedImage> imageSubscription;
    private ROS2Subscription<LaserScan> scanSubscription;
    private ROS2Subscription<OccupancyGrid> mapSubscription;
    private ROS2Subscription<PoseWithCovarianceStamped> amclPoseSubscription;
    private ROS2Subscription<Odometry> odomSubscription;
    private ROS2Subscription<BatteryState> batterySubscription;
    private ROS2Subscription<?> dynamicSubscription;

    private String currentSubTopic = "/chatter";
    private volatile boolean isRunning = false;
    private volatile long lastHeartbeatTime = 0;

    public ROS2NativeManager(Context context, ROS2DataListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setCmdVelType(CmdVelType type) {
        if (this.currentCmdVelType == type) return;
        this.currentCmdVelType = type;
        log("[cmd_vel 타입 설정] -> " + type.name());

        if (isRunning) {
            nodeExecutor.execute(() -> {
                synchronized (ROS2NativeManager.this) {
                    if (isRunning && ros2Node != null) {
                        createCmdVelPublisher();
                    }
                }
            });
        }
    }

    public CmdVelType getCmdVelType() {
        return currentCmdVelType;
    }

    public static boolean isValidRosName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String trimmed = name.trim();
        return trimmed.matches("^/[a-zA-Z0-9_/]+$");
    }

    private synchronized void createCmdVelPublisher() {
        if (ros2Node == null) return;
        cmdVelPublisher = null;
        cmdVelStampedPublisher = null;

        try {
            if (currentCmdVelType == CmdVelType.TWIST_STAMPED) {
                cmdVelStampedPublisher = ros2Node.createPublisher(new ROS2Topic<>("/cmd_vel", TwistStamped.class));
            } else {
                cmdVelPublisher = ros2Node.createPublisher(new ROS2Topic<>("/cmd_vel", Twist.class));
            }
            log("[cmd_vel 퍼블리셔 생성 완료]: " + currentCmdVelType.name());
        } catch (Throwable t) {
            Log.e(TAG, "cmd_vel 퍼블리셔 생성 예외: " + t.getMessage(), t);
        }
    }

    public void startNode(int domainId, boolean useBestEffortQos) {
        nodeExecutor.execute(() -> {
            synchronized (ROS2NativeManager.this) {
                cleanupInternal();

                try {
                    WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wifi != null) {
                        multicastLock = wifi.createMulticastLock("ros2_dds_multicast_lock");
                        multicastLock.setReferenceCounted(false);
                        multicastLock.acquire();
                    }

                    ros2Node = new ROS2Node("android_controller_node", domainId);
                    log("ROS2Node 생성 완료 (Domain: " + domainId + ")");

                    createCmdVelPublisher();
                    goalPublisher = ros2Node.createPublisher(new ROS2Topic<>("/goal_pose", PoseStamped.class));
                    initialPosePublisher = ros2Node.createPublisher(new ROS2Topic<>("/initialpose", PoseWithCovarianceStamped.class));

                    scanSubscription = ros2Node.createSubscription(new ROS2Topic<>("/scan", LaserScan.class), reader -> {
                        try {
                            LaserScan scan = reader.read();
                            if (scan != null && scan.getRanges() != null && listener != null) {
                                updateHeartbeat();
                                float[] ranges = scan.getRanges().getBuffer().array();
                                listener.onLaserScanReceived(ranges, scan.getAngleMin(), scan.getAngleIncrement());
                            }
                        } catch (Throwable ignored) {}
                    });

                    mapSubscription = ros2Node.createSubscription(new ROS2Topic<>("/map", OccupancyGrid.class), reader -> {
                        try {
                            OccupancyGrid map = reader.read();
                            if (map != null && map.getData() != null && listener != null) {
                                updateHeartbeat();
                                byte[] data = map.getData().getBuffer().array();
                                int w = map.getInfo().getWidth();
                                int h = map.getInfo().getHeight();
                                float res = map.getInfo().getResolution();
                                float ox = (float) map.getInfo().getOrigin().getPosition().getX();
                                float oy = (float) map.getInfo().getOrigin().getPosition().getY();
                                listener.onMapReceived(data, w, h, res, ox, oy);
                            }
                        } catch (Throwable ignored) {}
                    });

                    amclPoseSubscription = ros2Node.createSubscription(new ROS2Topic<>("/amcl_pose", PoseWithCovarianceStamped.class), reader -> {
                        try {
                            PoseWithCovarianceStamped poseMsg = reader.read();
                            if (poseMsg != null && listener != null) {
                                updateHeartbeat();
                                float x = (float) poseMsg.getPose().getPose().getPosition().getX();
                                float y = (float) poseMsg.getPose().getPose().getPosition().getY();
                                double qz = poseMsg.getPose().getPose().getOrientation().getZ();
                                double qw = poseMsg.getPose().getPose().getOrientation().getW();
                                float yaw = (float) (2.0 * Math.atan2(qz, qw));
                                listener.onRobotPoseReceived(x, y, yaw, true);
                            }
                        } catch (Throwable ignored) {}
                    });

                    odomSubscription = ros2Node.createSubscription(new ROS2Topic<>("/odom", Odometry.class), reader -> {
                        try {
                            Odometry odom = reader.read();
                            if (odom != null && listener != null) {
                                updateHeartbeat();
                                float x = (float) odom.getPose().getPose().getPosition().getX();
                                float y = (float) odom.getPose().getPose().getPosition().getY();
                                double qz = odom.getPose().getPose().getOrientation().getZ();
                                double qw = odom.getPose().getPose().getOrientation().getW();
                                float yaw = (float) (2.0 * Math.atan2(qz, qw));
                                listener.onRobotPoseReceived(x, y, yaw, false);
                            }
                        } catch (Throwable ignored) {}
                    });

                    batterySubscription = ros2Node.createSubscription(new ROS2Topic<>("/battery_state", BatteryState.class), reader -> {
                        try {
                            BatteryState bat = reader.read();
                            if (bat != null && listener != null) {
                                updateHeartbeat();
                                float pct = bat.getPercentage();
                                if (pct <= 1.0f && pct > 0.0f) pct *= 100f;
                                listener.onBatteryStateReceived(pct, bat.getVoltage(), bat.getPowerSupplyStatus() == 1);
                            }
                        } catch (Throwable ignored) {}
                    });

                    isRunning = true;
                    updateHeartbeat();
                    if (listener != null) {
                        listener.onStatusChanged("DDS 동작 중 (Domain: " + domainId + ")", true);
                    }

                } catch (Throwable t) {
                    Log.e(TAG, "노드 시작 예외", t);
                    cleanupInternal();
                    if (listener != null) {
                        listener.onStatusChanged("노드 생성 오류", false);
                        listener.onError("노드 생성 실패", "원인: " + t.getMessage());
                    }
                }
            }
        });
    }

    private void updateHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis();
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    /**
     * [Nav2 Goal 전송]: Frame, Timestamp, 정규화된 쿼터니언 적용
     */
    public synchronized void publishGoalPose(float worldX, float worldY, float yaw) {
        if (!isRunning || goalPublisher == null) {
            notifyError("Goal 전송 불가", "ROS 2 노드가 실행 중이 아닙니다.");
            return;
        }
        try {
            PoseStamped pose = new PoseStamped();
            long now = System.currentTimeMillis();

            if (pose.getHeader() != null) {
                pose.getHeader().setFrameId("map");
                if (pose.getHeader().getStamp() != null) {
                    pose.getHeader().getStamp().setSec((int) (now / 1000));
                    pose.getHeader().getStamp().setNanosec((int) ((now % 1000) * 1_000_000));
                }
            }
            if (pose.getPose() != null) {
                pose.getPose().getPosition().setX(worldX);
                pose.getPose().getPosition().setY(worldY);
                pose.getPose().getPosition().setZ(0.0);

                pose.getPose().getOrientation().setX(0.0);
                pose.getPose().getOrientation().setY(0.0);
                pose.getPose().getOrientation().setZ((float) Math.sin(yaw / 2.0));
                pose.getPose().getOrientation().setW((float) Math.cos(yaw / 2.0));
            }
            goalPublisher.publish(pose);
            log(String.format(Locale.getDefault(), "[Goal 전송] X: %.2f, Y: %.2f, Yaw: %.1f°", worldX, worldY, Math.toDegrees(yaw)));
        } catch (Throwable t) {
            notifyError("Goal 전송 오류", t.getMessage());
        }
    }

    /**
     * [2D Pose Estimate 핵심 수정]: AMCL 파티클 확산을 위한 Covariance 및 Timestamp 주입
     */
    public synchronized void publishInitialPose(float worldX, float worldY, float yaw) {
        if (!isRunning || initialPosePublisher == null) {
            notifyError("위치 추정 전송 불가", "ROS 2 노드가 실행 중이 아닙니다.");
            return;
        }
        try {
            PoseWithCovarianceStamped poseMsg = new PoseWithCovarianceStamped();
            long now = System.currentTimeMillis();

            // 1. 헤더 타임스탬프 설정 (TF Drop 방지)
            if (poseMsg.getHeader() != null) {
                poseMsg.getHeader().setFrameId("map");
                if (poseMsg.getHeader().getStamp() != null) {
                    poseMsg.getHeader().getStamp().setSec((int) (now / 1000));
                    poseMsg.getHeader().getStamp().setNanosec((int) ((now % 1000) * 1_000_000));
                }
            }

            // 2. 포즈 좌표 및 쿼터니언 방향 설정
            if (poseMsg.getPose() != null && poseMsg.getPose().getPose() != null) {
                poseMsg.getPose().getPose().getPosition().setX(worldX);
                poseMsg.getPose().getPose().getPosition().setY(worldY);
                poseMsg.getPose().getPose().getPosition().setZ(0.0);

                poseMsg.getPose().getPose().getOrientation().setX(0.0);
                poseMsg.getPose().getPose().getOrientation().setY(0.0);
                poseMsg.getPose().getPose().getOrientation().setZ((float) Math.sin(yaw / 2.0));
                poseMsg.getPose().getPose().getOrientation().setW((float) Math.cos(yaw / 2.0));
            }

            // 3. AMCL 입자 분포용 Covariance 설정 (X=0.25, Y=0.25, Yaw=0.0685)
            if (poseMsg.getPose() != null) {
                try {
                    double[] cov = new double[36];
                    cov[0] = 0.25;                         // X 분산 (표준편차 0.5m)
                    cov[7] = 0.25;                         // Y 분산 (표준편차 0.5m)
                    cov[35] = Math.pow(Math.PI / 12.0, 2); // Yaw 분산 (표준편차 15도)

                    if (poseMsg.getPose().getCovariance() != null) {
                        double[] target = poseMsg.getPose().getCovariance();
                        System.arraycopy(cov, 0, target, 0, cov.length);
                    }
                } catch (Throwable ignored) {}
            }

            initialPosePublisher.publish(poseMsg);

            if (listener != null) {
                listener.onRobotPoseReceived(worldX, worldY, yaw, true);
            }
            log(String.format(Locale.getDefault(), "[2D Pose Estimate] X: %.2f, Y: %.2f, Yaw: %.1f°", worldX, worldY, Math.toDegrees(yaw)));
        } catch (Throwable t) {
            notifyError("2D Pose Estimate 오류", t.getMessage());
        }
    }

    public synchronized void cancelNavigation(float currentRobotX, float currentRobotY, float currentRobotYaw) {
        try {
            publishCmdVel(0f, 0f);
            if (isRunning && goalPublisher != null) {
                publishGoalPose(currentRobotX, currentRobotY, currentRobotYaw);
            }
            log("[Nav2] 자율주행 취소 명령 전송 완료");
        } catch (Throwable t) {
            Log.e(TAG, "Nav 취소 예외", t);
        }
    }

    public synchronized void publishCmdVel(float linearX, float angularZ) {
        if (!isRunning) return;
        try {
            if (currentCmdVelType == CmdVelType.TWIST_STAMPED) {
                if (cmdVelStampedPublisher == null) return;
                TwistStamped twistStamped = new TwistStamped();
                long now = System.currentTimeMillis();
                if (twistStamped.getHeader() != null) {
                    twistStamped.getHeader().setFrameId("base_link");
                    if (twistStamped.getHeader().getStamp() != null) {
                        twistStamped.getHeader().getStamp().setSec((int) (now / 1000));
                        twistStamped.getHeader().getStamp().setNanosec((int) ((now % 1000) * 1_000_000));
                    }
                }
                if (twistStamped.getTwist() != null) {
                    twistStamped.getTwist().getLinear().setX(linearX);
                    twistStamped.getTwist().getAngular().setZ(angularZ);
                }
                cmdVelStampedPublisher.publish(twistStamped);
            } else {
                if (cmdVelPublisher == null) return;
                Twist twist = new Twist();
                if (twist.getLinear() != null) twist.getLinear().setX(linearX);
                if (twist.getAngular() != null) twist.getAngular().setZ(angularZ);
                cmdVelPublisher.publish(twist);
            }
        } catch (Throwable t) {
            Log.e(TAG, "cmd_vel 발행 예외", t);
        }
    }

    public synchronized void publishStringTopic(String topicName, String text) {
        if (!isRunning || ros2Node == null) return;
        try {
            ROS2Publisher<String_> publisher = stringPublisherMap.get(topicName);
            if (publisher == null) {
                ROS2Topic<String_> topic = new ROS2Topic<>(topicName, String_.class);
                publisher = ros2Node.createPublisher(topic);
                stringPublisherMap.put(topicName, publisher);
            }

            String_ msg = new String_();
            msg.setData(text == null ? "" : text);
            publisher.publish(msg);
            log("[Pub " + topicName + "]: " + text);
        } catch (Throwable t) {
            notifyError("토픽 발행 오류", t.getMessage());
        }
    }

    private synchronized void cleanupInternal() {
        isRunning = false;
        lastHeartbeatTime = 0;
        stringPublisherMap.clear();
        dynamicSubscription = null;
        scanSubscription = null;
        mapSubscription = null;
        amclPoseSubscription = null;
        odomSubscription = null;
        batterySubscription = null;
        cmdVelPublisher = null;
        cmdVelStampedPublisher = null;
        goalPublisher = null;
        initialPosePublisher = null;

        if (ros2Node != null) {
            try {
                ros2Node.close();
            } catch (Throwable ignored) {}
            ros2Node = null;
        }

        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) multicastLock.release();
            } catch (Throwable ignored) {}
            multicastLock = null;
        }
    }

    public void stopNode() {
        nodeExecutor.execute(() -> {
            synchronized (ROS2NativeManager.this) {
                cleanupInternal();
                if (listener != null) {
                    listener.onStatusChanged("상태: 노드 중지됨", false);
                }
            }
        });
    }

    public void shutdown() {
        stopNode();
        nodeExecutor.shutdown();
    }

    private void log(String message) {
        if (listener != null) listener.onLog(message);
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void notifyError(String title, String message) {
        log("[ERROR] " + title + ": " + message);
        if (listener != null) {
            listener.onError(title, message);
        }
    }
}