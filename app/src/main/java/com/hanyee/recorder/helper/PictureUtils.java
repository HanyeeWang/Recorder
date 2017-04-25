package com.hanyee.recorder.helper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class PictureUtils {

    public static Uri savePicture(byte[] data, int width, int height, File picFile) {

        Uri result = null;

        try {

            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            writeData2Bos(data, width, height, bos);

            if (!picFile.exists()) {
                picFile.createNewFile();
            }

            FileOutputStream fos = new FileOutputStream(picFile);
            fos.write(bos.toByteArray());
            fos.close();
            bos.close();

            result = Uri.fromFile(picFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        return result;

    }

    private static void writeData2Bos(byte[] data, int width, int height, ByteArrayOutputStream bos) throws IOException {
        Bitmap originBitmap = decodeData2Bitmap(data, width, height);
        Bitmap rotatedBitmap = rotateBitmap(originBitmap, 90.0f, bos);

        originBitmap.recycle();
        rotatedBitmap.recycle();
    }

    public static Bitmap decodeData2Bitmap(byte[] data, int width, int height) throws IOException {

        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, width, height, null);

        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, bos);

        byte[] byteArray = bos.toByteArray();

        Bitmap result = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);

        bos.close();

        return result;
    }


    public static Bitmap rotateBitmap(Bitmap sourceBitmap, float rotate, ByteArrayOutputStream bos) {

        Matrix matrix = new Matrix();
        matrix.setRotate(rotate);

        Bitmap result = Bitmap.createBitmap(sourceBitmap, 0, 0,
                sourceBitmap.getWidth(),
                sourceBitmap.getHeight(),
                matrix, true);

        result.compress(Bitmap.CompressFormat.JPEG, 100, bos);

        return result;
    }
}
