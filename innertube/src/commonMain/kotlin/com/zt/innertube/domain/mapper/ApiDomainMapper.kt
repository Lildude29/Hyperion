package com.zt.innertube.domain.mapper

import com.zt.innertube.domain.model.DomainChannelPartial
import com.zt.innertube.domain.model.DomainStream
import com.zt.innertube.domain.model.DomainVideoPartial
import com.zt.innertube.network.dto.ApiFormat
import com.zt.innertube.network.dto.ApiNextVideo
import com.zt.innertube.network.dto.ApiVideo

internal fun ApiVideo.ContextData.toDomain() = DomainVideoPartial(
    id = onTap.innertubeCommand.watchEndpoint?.videoId.orEmpty(),
    title = videoData.metadata.title,
    subtitle = videoData.metadata.metadataDetails,
    timestamp = videoData.thumbnail.timestampText,
    channel = videoData.avatar?.let { avatar ->
        DomainChannelPartial(
            id = avatar.endpoint.innertubeCommand.browseEndpoint.browseId,
            avatarUrl = avatar.image.sources.last().url
        )
    } ?: DomainChannelPartial(
        id = videoData.channelId!!,
        avatarUrl = videoData.decoratedAvatar!!.avatar.image.sources.last().url
    )
)

internal fun ApiNextVideo.ContextData.toDomain() = DomainVideoPartial(
    id = onTap.innertubeCommand.watchNextWatchEndpointMutationCommand?.watchEndpoint?.watchEndpoint?.videoId.orEmpty(),
    title = videoData.metadata.title,
    subtitle = videoData.metadata.metadataDetails,
    timestamp = videoData.thumbnail.timestampText,
    channel = videoData.avatar?.let { avatar ->
        DomainChannelPartial(
            id = avatar.endpoint.innertubeCommand.browseEndpoint.browseId,
            avatarUrl = avatar.image.sources.last().url
        )
    }
)

internal fun ApiFormat.toDomain() = when {
    mimeType.startsWith("video/") -> DomainStream.Video(
        url = url,
        itag = itag,
        label = qualityLabel!!,
        mimeType = mimeType
    )
    mimeType.startsWith("audio/") -> DomainStream.Audio(
        url = url,
        itag = itag,
        mimeType = mimeType
    )
    else -> throw NoWhenBranchMatchedException(toString())
}