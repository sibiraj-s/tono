package app.tono.ui

import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.UnknownHostException

/** Plain-language message for errors shown in the UI. */
fun Throwable.userMessage(): String = when (this) {
    is UnknownHostException -> "You're offline. Check your connection and try again."
    is ReCaptchaException -> "YouTube is asking this device to slow down. Try again in a few minutes."
    is AgeRestrictedContentException -> "This video is age-restricted, so it can't be used."
    is GeographicRestrictionException -> "This video isn't available in your country."
    is ContentNotAvailableException -> "This video isn't available."
    is ParsingException -> "YouTube changed something Tono relies on. Updating the app usually fixes this."
    is IOException -> message ?: "Network error. Try again."
    else -> message ?: "Something went wrong."
}
