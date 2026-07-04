package com.example.aigallery.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器
 *
 * 当 App 任意线程发生未捕获异常时，Android 系统会回调 [uncaughtException]。
 *
 * 职责：
 * 1. 将完整崩溃堆栈写入 App 私有目录（方便开发者调试，无需额外权限）
 * 2. 启动 [CrashActivity] 展示友好错误页，替代系统默认的"已停止运行"弹窗
 * 3. 主动结束崩溃进程，确保系统不会在此之上叠加错误弹窗
 *
 * 注册时机：[com.example.aigallery.AIGalleryApp.onCreate] 中，最早注册
 *
 * @param applicationContext 用于启动 Activity 和写文件
 * @param defaultHandler     系统原始处理器，作为兜底备份保留
 */
class GlobalCrashHandler(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 第一步：将崩溃堆栈持久化到本地文件
            saveCrashLog(thread, throwable)
            // 第二步：启动友好崩溃提示页
            launchCrashScreen()
        } catch (secondary: Exception) {
            // 若处理器自身抛出异常，退回到系统默认处理器，避免无限递归
            Log.e(TAG, "CrashHandler 自身发生异常", secondary)
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        // 等待 400ms，让 ActivityManager 有时间接收 startActivity 请求
        // 之后结束崩溃进程，防止系统再次弹出 ANR / "已停止运行"提示
        try { Thread.sleep(400) } catch (_: InterruptedException) { }
        Process.killProcess(Process.myPid())
        System.exit(1)
    }

    /**
     * 将崩溃信息写入 App 私有文件目录
     *
     * 存储路径：/data/data/com.example.aigallery/files/crash/crash_<时间戳>.txt
     * - 属于 App 沙箱，无需任何权限
     * - 只保留最近 5 条，超出自动清理，防止占用过多空间
     */
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        try {
            val crashDir = File(applicationContext.filesDir, "crash").also { it.mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val logFile = File(crashDir, "crash_$timestamp.txt")

            logFile.writeText(
                buildString {
                    appendLine("=== 忆刻 崩溃日志 ===")
                    appendLine("时间：$timestamp")
                    appendLine("崩溃线程：${thread.name}（id=${thread.id}）")
                    appendLine("============================")
                    appendLine()
                    appendLine(throwable.stackTraceToString())
                }
            )

            // 按修改时间降序，超过 5 条的旧文件自动删除
            crashDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }

        } catch (e: Exception) {
            // 写文件失败不影响后续的友好提示页展示
            Log.w(TAG, "写崩溃日志失败", e)
        }
    }

    /**
     * 启动崩溃友好提示页
     *
     * FLAG_ACTIVITY_NEW_TASK   — 在新 Task 中启动（必须，因为当前 Task 已处于崩溃状态）
     * FLAG_ACTIVITY_CLEAR_TASK — 清除原有回退栈，确保用户按返回键不会回到崩溃界面
     */
    private fun launchCrashScreen() {
        val intent = Intent(applicationContext, CrashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val TAG = "GlobalCrashHandler"
    }
}
