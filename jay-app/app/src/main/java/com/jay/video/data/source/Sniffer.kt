package com.jay.video.data.source

/** 影视仓嗅探规则（对照 com.fongmi.android.tv.utils.Sniffer 移植） */
object Sniffer {

    /** 视频地址嗅探正则（与影视仓一致） */
    val SNIFFER = Regex("https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+")

    /** 播放器跳转链识别（player 页面再跳真实播放器） */
    val PLAYER = Regex("player.*https?://")

    /** 是否视频格式地址（与影视仓 isVideoFormat 一致） */
    fun isVideoFormat(url: String): Boolean {
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false
        return SNIFFER.containsMatchIn(url)
    }
}
