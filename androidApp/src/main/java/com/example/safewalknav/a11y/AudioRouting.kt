package com.example.safewalknav.a11y

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** 볼륨 키·출력 장치 관련 공통 처리. */
object AudioRouting {

    /**
     * 이 화면에서 볼륨 **올리기** 키가 앱 음성의 볼륨을 움직이게 한다.
     *
     * 왜: 아무 소리도 안 날 때 볼륨 올리기를 누르면, 기기 설정에 따라 벨소리 볼륨이 올라가
     * 안내는 계속 안 들렸다. 앱 음성은 접근성 용도라
     *  - TalkBack 켜짐: 접근성 볼륨을 따로 탄다 → STREAM_ACCESSIBILITY
     *  - TalkBack 꺼짐: 접근성 볼륨이 미디어 볼륨을 따라간다 → STREAM_MUSIC
     */
    fun bindVolumeKeys(activity: Activity) {
        activity.volumeControlStream = speechStream(activity)
    }

    fun speechStream(context: Context): Int =
        if (Speaker.isScreenReaderOn(context)) AudioManager.STREAM_ACCESSIBILITY else AudioManager.STREAM_MUSIC

    /**
     * 블루투스(이어폰·골전도·보청기)로 소리가 나가는가.
     * 이런 기기는 잠깐 조용하면 절전 대기에 들어가, 다시 소리가 날 때 앞 0.2~0.5초가 잘린다 —
     * "빨간불"이 "…불"로 들린다. 그래서 말 앞에 짧은 무음을 넣어 깨운다.
     */
    fun isBluetoothOutput(context: Context): Boolean {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_TYPES }
        } catch (_: Exception) {
            false
        }
    }

    /** 블루투스 기기를 깨우는 무음 길이. */
    const val BLUETOOTH_WAKE_MS = 250L

    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,   // API 28+
        AudioDeviceInfo.TYPE_BLE_HEADSET,   // API 31+
        AudioDeviceInfo.TYPE_BLE_SPEAKER,   // API 31+
    )
}
