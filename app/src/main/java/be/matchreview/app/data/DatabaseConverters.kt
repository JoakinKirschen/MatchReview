package be.matchreview.app.data

import androidx.room.TypeConverter

class DatabaseConverters {
    @TypeConverter fun fromMatchStatus(value: MatchStatus): String = value.name
    @TypeConverter fun toMatchStatus(value: String): MatchStatus = enumValueOf(value)

    @TypeConverter fun fromAvailability(value: AvailabilityStatus): String = value.name
    @TypeConverter fun toAvailability(value: String): AvailabilityStatus = enumValueOf(value)

    @TypeConverter fun fromPlayerState(value: PlayerMatchState): String = value.name
    @TypeConverter fun toPlayerState(value: String): PlayerMatchState = enumValueOf(value)

    @TypeConverter fun fromPeriodStatus(value: PeriodStatus): String = value.name
    @TypeConverter fun toPeriodStatus(value: String): PeriodStatus = enumValueOf(value)

    @TypeConverter fun fromRecordingStatus(value: RecordingStatus): String = value.name
    @TypeConverter fun toRecordingStatus(value: String): RecordingStatus = enumValueOf(value)

    @TypeConverter fun fromStopReason(value: StopReason?): String? = value?.name
    @TypeConverter fun toStopReason(value: String?): StopReason? = value?.let(StopReason::valueOf)

    @TypeConverter fun fromParticipationReason(value: ParticipationReason?): String? = value?.name
    @TypeConverter fun toParticipationReason(value: String?): ParticipationReason? = value?.let(ParticipationReason::valueOf)
}
