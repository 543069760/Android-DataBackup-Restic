package com.xayah.core.rootservice;

interface ICallback {
    void onProgress(long bytesWritten, long speed, float progress);
    // 新增：restore 规划阶段一次性回传统计
    void onRestorePlan(long filesTotal, long bytesTotal, long filesSkipped, long bytesSkipped);
}