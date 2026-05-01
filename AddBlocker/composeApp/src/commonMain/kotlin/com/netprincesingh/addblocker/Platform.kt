package com.netprincesingh.addblocker

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform