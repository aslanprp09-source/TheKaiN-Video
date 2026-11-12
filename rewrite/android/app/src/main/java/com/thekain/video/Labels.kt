package com.thekain.video

import com.thekain.video.domain.*

fun TransferState.label(): Int = when (this) {
    TransferState.QUEUED -> R.string.state_queued
    TransferState.DOWNLOADING -> R.string.state_downloading
    TransferState.PAUSING -> R.string.state_pausing
    TransferState.PAUSED -> R.string.state_paused
    TransferState.VERIFYING -> R.string.state_verifying
    TransferState.PUBLISHING -> R.string.state_publishing
    TransferState.RETRY_WAIT -> R.string.state_retry_wait
    TransferState.COMPLETED -> R.string.state_completed
    TransferState.FAILED -> R.string.state_failed
    TransferState.CANCEL_REQUESTED -> R.string.state_cancel_requested
    TransferState.CANCELED -> R.string.state_canceled
}
fun TransferError.label(): Int = when (this) {
    TransferError.INVALID_URL -> R.string.error_invalid_url
    TransferError.REMOTE_FORBIDDEN -> R.string.error_remote_forbidden
    TransferError.REMOTE_MISSING -> R.string.error_remote_missing
    TransferError.RATE_LIMITED -> R.string.error_rate_limited
    TransferError.REMOTE_SERVER -> R.string.error_remote_server
    TransferError.NETWORK_LOST -> R.string.error_network_lost
    TransferError.TIMEOUT -> R.string.error_timeout
    TransferError.NO_DISK_SPACE -> R.string.error_no_disk_space
    TransferError.PERMISSION_DENIED -> R.string.error_permission_denied
    TransferError.VERIFY_FAILED -> R.string.error_verify_failed
    TransferError.RESUME_UNSUPPORTED -> R.string.error_resume_unsupported
    TransferError.INTERRUPTED -> R.string.error_interrupted
    TransferError.BACKGROUND_RESTRICTED -> R.string.error_background_restricted
    TransferError.DRM_PROTECTED -> R.string.error_drm_protected
    TransferError.UNKNOWN -> R.string.error_unknown
}
