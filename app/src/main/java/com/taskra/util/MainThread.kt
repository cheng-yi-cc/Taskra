package com.taskra.util

import android.os.Handler
import android.os.Looper

/**
 * 导航/界面回调必须在主线程执行。
 * Room 挂起函数恢复时的线程取决于调用方调度器（生产是 Main，测试可能是测试调度器），
 * 因此跟随 DB 操作的导航回调统一经此切回真实主线程，生产与测试行为一致。
 */
fun postOnMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action()
    } else {
        Handler(Looper.getMainLooper()).post(action)
    }
}
