package com.xayah.core.rootservice;

interface ICallback {
    void onProgress(long bytesWritten, long speed, float progress);
}