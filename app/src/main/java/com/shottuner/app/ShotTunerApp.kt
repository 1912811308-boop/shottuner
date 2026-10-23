package com.shottuner.app

import android.app.Application
import android.os.Process
import android.util.Log
import com.shottuner.app.diag.Diag

/**
 * 应用入口（v3.0.0 阶段 3）。
 *
 * 只做两件事：初始化本地诊断日志、安装全局未捕获异常处理器。
 * 没有任何 SDK 初始化、没有网络、没有第三方上报。
 */
class ShotTunerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        installCrashHandler()
    }

    /**
     * 全局未捕获异常处理。
     *
     * **顺序是这个方法的全部意义：先落盘，再转发。**
     *
     * 1. 先同步写盘。进程马上就会被杀，任何"异步写日志"都会丢 —— 这是唯一还有机会
     *    留下现场的时刻。
     * 2. **绝对不能吞掉崩溃**。如果把异常拦下来什么都不做，系统就不知道这个进程已经
     *    不可用：Activity 不会销毁、Looper 不会再转，用户看到的是一个"点了没反应、
     *    也不报错"的僵尸界面 —— 比直接崩掉更难排查，也更不体面。
     *    所以最后一定要把控制权交回**原来的** handler（系统自带的
     *    KillApplicationHandler），由它完成"记录 + 杀进程 + 下次启动弹崩溃提示"。
     * 3. 万一 previous 为 null（理论上不该发生），就自己结束进程，绝不能 return。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Diag.e("CRASH", "线程 ${thread.name} 抛出未捕获异常", error)
                // 额外写一条到 logcat：开发者用 adb 时不必先导出文件
                Log.e("ShotTuner", "uncaught on ${thread.name}", error)
            } catch (_: Throwable) {
                // 记日志本身失败也必须继续往下走 —— 不能因为记不下来就不杀进程
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                @Suppress("DEPRECATION")
                System.exit(10)
            }
        }
    }
}
