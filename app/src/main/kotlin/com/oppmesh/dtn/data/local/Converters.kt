package com.oppmesh.dtn.data.local

import androidx.room.TypeConverter
import com.oppmesh.dtn.domain.model.RoutingStrategy

class Converters {
    @TypeConverter
    fun fromRoutingStrategy(strategy: RoutingStrategy): String = strategy.name

    @TypeConverter
    fun toRoutingStrategy(value: String): RoutingStrategy =
        try {
            RoutingStrategy.valueOf(value)
        } catch (e: Exception) {
            RoutingStrategy.DIRECT_DELIVERY
        }
}
