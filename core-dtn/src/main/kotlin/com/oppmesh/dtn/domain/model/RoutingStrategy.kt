package com.oppmesh.dtn.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingStrategy {
    DIRECT_DELIVERY,
    EPIDEMIC,
    SPRAY_AND_WAIT
}
