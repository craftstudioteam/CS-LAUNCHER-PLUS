package net.kdt.pojavlaunch.modloaders.modpacks.imagecache;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.FileOutputStream;
import java.io.IOException;

class DownloadImageTask implements Runnable {
    private static final float BITMAP_FINAL_DIMENSION = 256f;
    private final ReadFromDiskTask mParentTask;
    private int mRetryCount;
    DownloadImageTask(ReadFromDiskTask parentTask) {
        this.mParentTask = parentTask;
        this.mRetryCount = 0;
    }

    @Override
    public void run() {
        boolean wasSuccessful = false;
        while(mRetryCount < 2 && !(wasSuccessful = runCatching())) {
            mRetryCount++;
        }
        // restart the parent task to read the image and send it to the receiver
        // if it wasn't cancelled. If it was, then we just die here
        if(wasSuccessful && !mParentTask.taskCancelled())
            mParentTask.iconCache.getCacheLoaderPool().execute(mParentTask);
    }

    public boolean runCatching() {
        try {
            DownloadUtils.downloadFile(mParentTask.imageUrl, mParentTask.cacheFile);
            boolean banner = mParentTask.cacheFile.getName().contains("_bg");
            float target = banner ? 640f : BITMAP_FINAL_DIMENSION;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(mParentTask.cacheFile.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            int maxSide = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxSide / (sample * 2) >= target) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            if (banner) options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(mParentTask.cacheFile.getAbsolutePath(), options);
            if (bitmap == null) return false;
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            if (width <= target && height <= target && sample == 1) {
                bitmap.recycle();
                return true;
            }
            float ratio = Math.min(1f, Math.min(target / width, target / height));
            Bitmap resized = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, (int) (width * ratio)),
                    Math.max(1, (int) (height * ratio)), true);
            if (resized != bitmap) bitmap.recycle();
            try (FileOutputStream output = new FileOutputStream(mParentTask.cacheFile)) {
                resized.compress(Bitmap.CompressFormat.JPEG, banner ? 76 : 82, output);
            } finally {
                resized.recycle();
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
