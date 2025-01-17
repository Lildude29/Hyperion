package com.hyperion.di

import com.zt.innertube.network.service.InnerTubeService
import com.zt.innertube.network.service.RYDService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val serviceModule = module {
    singleOf(::InnerTubeService)
    singleOf(::RYDService)
}