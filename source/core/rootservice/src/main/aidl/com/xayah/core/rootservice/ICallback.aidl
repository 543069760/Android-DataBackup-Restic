package com.xayah.core.rootservice;

interface ICallback {
    void onProgress(long readBytes, long readTotal, float readProgress, long writtenBytes, long writtenSpeed);
    // 新增：restore 规划阶段一次性回传统计
    void onRestorePlan(long filesTotal, long bytesTotal, long filesSkipped, long bytesSkipped);
}