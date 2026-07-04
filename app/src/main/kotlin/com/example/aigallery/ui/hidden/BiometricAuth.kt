package com.example.aigallery.ui.hidden

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 隐藏相册访问验证：进入前用系统级验证挡一道。
 *
 * 直接复用设备已有的锁屏方式（指纹/人脸优先，未录入或不可用时自动回退到 PIN/图案/密码），
 * 而不是自建一套密码存储 + 校验 UI——最简单，也最安全（不用操心"我方存的密码"泄露问题，
 * 且用户不需要额外记一个新密码）。
 */
private const val ALLOWED_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

/**
 * 设备是否已设置可用的验证方式（指纹/人脸/PIN/图案/密码任意一种）。
 *
 * 极少数设备完全没有设置任何锁屏方式时返回 false——此时不阻拦用户
 * （总不能让隐藏相册因为对方设备没锁屏而永远打不开），调用方应直接放行。
 */
fun canUseBiometricAuth(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(ALLOWED_AUTHENTICATORS) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** 弹出系统验证框；成功/失败通过回调通知，取消也会经由 [onError] 回调（errorCode 会是用户取消码） */
fun showHiddenAlbumAuthPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
            // onAuthenticationFailed（单次尝试失败，如指纹不匹配）：系统弹窗自己会继续提示重试，无需处理
        }
    )
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("验证身份")
        .setSubtitle("需要验证才能查看隐藏相册")
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        .build()
    prompt.authenticate(promptInfo)
}
