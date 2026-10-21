package dev.unifieddesktop.app

/**
 * Phone-first resource policy. This file deliberately has no Android dependencies so the
 * thresholds and ordering can be tested on the JVM. The service supplies the Android
 * measurements and performs the side effects.
 */
enum class Phase10GovernorStage {
    NORMAL,
    HIDDEN_STOPPED,
    FPS_30,
    FPS_15,
    LOW_RESOLUTION,
    WARNED,
}

sealed class Phase10GovernorAction {
    data object None : Phase10GovernorAction()
    data object StopHiddenWindows : Phase10GovernorAction()
    data class SetFps(val fps: Int) : Phase10GovernorAction()
    data object LowerResolution : Phase10GovernorAction()
    data object WarnPc : Phase10GovernorAction()
}

data class Phase10GovernorInput(
    val lowMemory: Boolean,
    val trimLevel: Int,
    val memAvailableKb: Long,
    val thermalStatus: Int,
    val desktopVisible: Boolean,
)

data class Phase10GovernorDecision(
    val previous: Phase10GovernorStage,
    val next: Phase10GovernorStage,
    val action: Phase10GovernorAction,
    val trigger: String,
)

object Phase10Governor {
    /** Android's TRIM_MEMORY_RUNNING_LOW. Kept numeric to keep this policy pure. */
    const val TRIM_MEMORY_RUNNING_LOW = 10
    /** Android's THERMAL_STATUS_MODERATE. Kept numeric to keep this policy pure. */
    const val THERMAL_STATUS_MODERATE = 2
    const val MIN_AVAILABLE_KB = 256L * 1024L

    fun pressure(input: Phase10GovernorInput): String? {
        if (input.lowMemory) return "lowMemory=true"
        if (input.trimLevel >= TRIM_MEMORY_RUNNING_LOW) return "trim=${input.trimLevel}"
        if (input.memAvailableKb in 0 until MIN_AVAILABLE_KB) {
            return "memAvailableKb=${input.memAvailableKb}"
        }
        if (input.thermalStatus >= THERMAL_STATUS_MODERATE) {
            return "thermal=${input.thermalStatus}"
        }
        return null
    }

    fun next(
        stage: Phase10GovernorStage,
        input: Phase10GovernorInput,
    ): Phase10GovernorDecision {
        val trigger = pressure(input)
        if (trigger == null) {
            return Phase10GovernorDecision(stage, stage, Phase10GovernorAction.None, "healthy")
        }
        return when (stage) {
            Phase10GovernorStage.NORMAL -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.HIDDEN_STOPPED,
                Phase10GovernorAction.StopHiddenWindows,
                trigger,
            )
            Phase10GovernorStage.HIDDEN_STOPPED -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.FPS_30,
                Phase10GovernorAction.SetFps(30),
                trigger,
            )
            Phase10GovernorStage.FPS_30 -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.FPS_15,
                Phase10GovernorAction.SetFps(15),
                trigger,
            )
            Phase10GovernorStage.FPS_15 -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.LOW_RESOLUTION,
                Phase10GovernorAction.LowerResolution,
                trigger,
            )
            Phase10GovernorStage.LOW_RESOLUTION -> Phase10GovernorDecision(
                stage,
                Phase10GovernorStage.WARNED,
                Phase10GovernorAction.WarnPc,
                trigger,
            )
            Phase10GovernorStage.WARNED -> Phase10GovernorDecision(
                stage,
                stage,
                Phase10GovernorAction.None,
                trigger,
            )
        }
    }
}
