package com.xayah.libnative

object TarWrapper {
    external fun callCli(stdOut: String, stdErr: String, argv: Array<String>): Int
    external fun callCliFd(outFd: Int, stdErr: String, argv: Array<String>, cancelFd: Int, cancelWriteFd: Int): Int
}