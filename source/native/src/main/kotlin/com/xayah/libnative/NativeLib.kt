package com.xayah.libnative

object NativeLib {
    /**
     * 累计 [path] 下所有条目的大小（字节）。
     *
     * 遍历不跟随符号链接（nftw + FTW_PHYS，语义等同 lstat）：符号链接只计链接自身大小，
     * 绝不递归进目标。旧实现用 ftw(3)（总是 stat 跟随链接），在符号链接密集的 rootfs 上
     * 会反复进入同一批目录而永不返回。
     *
     * 另有条目数 / 时长 / 深度上限兜底：触限时不再无限遍历，而是降级返回已累计的部分值。
     *
     * @param path 待统计路径；若其本身是符号链接，会先展开成真实目录再统计。
     */
    external fun calculateSize(path: String): Long

    external fun getUidGid(path: String): IntArray
}
