package com.hanyee.recorder;

import java.io.Serializable;

public class ImageInfo implements Serializable {

    private int id;
    private String imageIndex;
    private String imageName;
    private String showTime;

    public ImageInfo() {
    }

    public int getId() {
        return id;
    }

    public String getImageIndex() {
        return imageIndex;
    }

    public void setImageIndex(String imageIndex) {
        this.imageIndex = imageIndex;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getShowTime() {
        return showTime;
    }

    public void setShowTime(String showTime) {
        this.showTime = showTime;
    }

}
