package com.example.robot_agent;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

public class MapSaverLocal {
    private static final String TAG = "MapSaverLocal";

    public static String saveMapToDevice(Context context, byte[] mapData, int width, int height, float resolution, float originX, float originY, String mapName) {
        if (mapData == null || width <= 0 || height <= 0) {
            return "ERROR: 저장할 맵 데이터가 없습니다.";
        }

        try {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File mapDir = new File(downloadDir, "ROS2_Maps");
            if (!mapDir.exists()) {
                mapDir.mkdirs();
            }

            String cleanName = (mapName == null || mapName.trim().isEmpty()) ? "my_map" : mapName.trim();
            File pgmFile = new File(mapDir, cleanName + ".pgm");
            File yamlFile = new File(mapDir, cleanName + ".yaml");

            try (FileOutputStream fos = new FileOutputStream(pgmFile);
                 FileWriter fw = new FileWriter(yamlFile)) {

                // 1. PGM 바이너리 (P5 포맷) 파일 생성
                String header = String.format(Locale.US, "P5\n%d %d\n255\n", width, height);
                fos.write(header.getBytes());

                byte[] pgmPixels = new byte[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int srcIdx = (height - 1 - y) * width + x;
                        if (srcIdx < mapData.length) {
                            byte val = mapData[srcIdx];
                            byte pgmVal;
                            if (val == 0) {
                                pgmVal = (byte) 254;       // 자유 공간 (흰색)
                            } else if (val == 100) {
                                pgmVal = (byte) 0;         // 장애물/벽 (검은색)
                            } else {
                                pgmVal = (byte) 205;       // 미탐색 영역 (회색)
                            }
                            pgmPixels[y * width + x] = pgmVal;
                        }
                    }
                }
                fos.write(pgmPixels);
                fos.flush();

                // 2. YAML 메타데이터 파일 생성
                fw.write(String.format(Locale.US, "image: %s.pgm\n", cleanName));
                fw.write("mode: trinary\n");
                fw.write(String.format(Locale.US, "resolution: %.4f\n", resolution));
                fw.write(String.format(Locale.US, "origin: [%.4f, %.4f, 0.0]\n", originX, originY));
                fw.write("negate: 0\n");
                fw.write("occupied_thresh: 0.65\n");
                fw.write("free_thresh: 0.25\n");
                fw.flush();
            }

            if (context != null) {
                MediaScannerConnection.scanFile(context, new String[]{pgmFile.getAbsolutePath(), yamlFile.getAbsolutePath()}, null, null);
            }

            return "SUCCESS: 스마트폰 [내 파일 ➔ Download ➔ ROS2_Maps] 폴더에 저장 완료!\n파일명: " + cleanName + ".yaml / .pgm";

        } catch (IOException e) {
            Log.e(TAG, "맵 저장 오류", e);
            return "ERROR: 맵 파일 저장 실패: " + e.getMessage();
        }
    }
}