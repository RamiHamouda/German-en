package com.bnyro

import com.lagradost.cloudstream3.extractors.Chillx
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.extractors.Vidguardto

class KinogerRu : Chillx() {
    override val name = "KinogerRu"
    override val mainUrl = "https://kinoger.ru"
}

class KinogerBe : VidHidePro() {
    override var name = "KinogerBe"
    override var mainUrl = "https://kinoger.be"
}

class KinogerPw : Vidguardto() {
    override var name = "KinogerPw"
    override var mainUrl = "https://kinoger.pw"
}

class KinogerRe : VidStack() {
    override var name = "KinogerRe"
    override var mainUrl = "https://kinoger.re"
}