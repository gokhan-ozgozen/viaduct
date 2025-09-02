package viaduct.service.api.spi

import viaduct.service.api.spi.Flags.EXECUTE_ACCESS_CHECKS_IN_MODERN_EXECUTION_STRATEGY

/**
 * Interface for managing feature flags.
 */
interface FlagManager {
    /**
     * Returns a boolean representing whether or not [flag] is enabled. Impl should execute very quickly as it could
     * be used in the hot path.
     */
    fun isEnabled(flag: Flag): Boolean

    object disabled : FlagManager {
        override fun isEnabled(flag: Flag): Boolean = false
    }

    object default : FlagManager {
        override fun isEnabled(flag: Flag): Boolean =
            when (flag) {
                EXECUTE_ACCESS_CHECKS_IN_MODERN_EXECUTION_STRATEGY -> true
                else -> false
            }
    }
}
