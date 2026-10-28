package io.github.jackwener.dsh.engine

// Package must match the JNI symbol exported by native/engine-jni/dshengine.cc.

/** JNI entry into libdshengine.so, which calls node::Start on the given fds. */
object NodeRuntime {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("dshengine")
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
