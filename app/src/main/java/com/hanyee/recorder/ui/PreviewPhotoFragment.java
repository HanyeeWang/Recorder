package com.hanyee.recorder.ui;


import android.app.Fragment;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.widget.ImageView;

import com.hanyee.recorder.Constants;
import com.hanyee.recorder.helper.ImageUtils;
import com.hanyee.recorder.R;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EFragment;
import org.androidannotations.annotations.FragmentArg;
import org.androidannotations.annotations.UiThread;
import org.androidannotations.annotations.ViewById;

@EFragment(R.layout.fragment_preview_photo)
public class PreviewPhotoFragment extends Fragment {

    @FragmentArg(Constants.FragmentKey.IMG_PATH)
    String mPath;
    @ViewById(R.id.preImageView)
    ImageView mImageView;

    @AfterViews
    @UiThread(delay = 1000l)
    public void showImage() {
        new ImageLoadTask().execute(mImageView.getWidth(), mImageView.getHeight());
    }

    private class ImageLoadTask extends AsyncTask<Integer, Integer, Bitmap> {

        @Override
        protected Bitmap doInBackground(Integer... args) {
            Bitmap bm = null;
            try {
                bm = ImageUtils.resizeBitmap(mPath, args[0], args[1]);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return bm;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                mImageView.setImageBitmap(bitmap);
                mImageView.setScaleType(ImageView.ScaleType.CENTER);
            } else {
                bitmap = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_default);
                mImageView.setImageBitmap(bitmap);
                mImageView.setScaleType(ImageView.ScaleType.CENTER);
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        System.gc();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Drawable drawable = mImageView.getDrawable();
        if (drawable != null) {
            if (drawable instanceof BitmapDrawable) {
                if (((BitmapDrawable) drawable).getBitmap() != null)
                    ((BitmapDrawable) drawable).getBitmap().recycle();
            }
        }
        mImageView.setImageBitmap(null);
        System.gc();
    }

}
