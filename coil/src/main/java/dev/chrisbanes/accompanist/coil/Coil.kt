/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.chrisbanes.accompanist.coil

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Box
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.launchInComposition
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.stateFor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.WithConstraints
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageAsset
import androidx.compose.ui.graphics.asImageAsset
import androidx.compose.ui.graphics.painter.ImagePainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ContextAmbient
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.ImageResult

/**
 * Creates a composable that will attempt to load the given [data] using [Coil], and then
 * display the result in an [Image].
 *
 * @param data The data to load. See [ImageRequest.Builder.data] for the types allowed.
 * @param modifier [Modifier] used to adjust the layout algorithm or draw decoration content.
 * @param alignment Optional alignment parameter used to place the loaded [ImageAsset] in the
 * given bounds defined by the width and height.
 * @param contentScale Optional scale parameter used to determine the aspect ratio scaling to be
 * used if the bounds are a different size from the intrinsic size of the loaded [ImageAsset].
 * @param colorFilter Optional colorFilter to apply for the [Painter] when it is rendered onscreen.
 * @param getSuccessPainter Optional builder for the [Painter] to be used to draw the successful
 * loading result. Passing in `null` will result in falling back to the default [Painter].
 * @param getFailurePainter Optional builder for the [Painter] to be used to draw the failure
 * loading result. Passing in `null` will result in falling back to the default [Painter].
 * @param loading Content to be displayed when the request is in progress.
 * @param shouldRefetchOnSizeChange Lambda which will be invoked when the size changes, allowing
 * optional re-fetching of the image. Return true to re-fetch the image.
 * @param onRequestCompleted Listener which will be called when the loading request has finished.
 */
@Composable
fun CoilImage(
    data: Any,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    getSuccessPainter: @Composable ((SuccessResult) -> Painter)? = null,
    getFailurePainter: @Composable ((ErrorResult) -> Painter?)? = null,
    loading: @Composable (() -> Unit)? = null,
    shouldRefetchOnSizeChange: (currentResult: RequestResult, size: IntSize) -> Boolean = defaultRefetchOnSizeChangeLambda,
    onRequestCompleted: (RequestResult) -> Unit = emptySuccessLambda
) {
    CoilImage(
        request = when (data) {
            // If the developer is accidentally using the wrong function (data vs request), just
            // pass the request through
            is ImageRequest -> data
            // Otherwise we construct a GetRequest using the data parameter
            else -> {
                val context = ContextAmbient.current
                remember(data) { ImageRequest.Builder(context).data(data).build() }
            }
        },
        alignment = alignment,
        contentScale = contentScale,
        colorFilter = colorFilter,
        onRequestCompleted = onRequestCompleted,
        getSuccessPainter = getSuccessPainter,
        getFailurePainter = getFailurePainter,
        loading = loading,
        shouldRefetchOnSizeChange = shouldRefetchOnSizeChange,
        modifier = modifier
    )
}

/**
 * Creates a composable that will attempt to load the given [request] using [Coil], and then
 * display the result in an [Image].
 *
 * @param request The request to execute. If the request does not have a [ImageRequest.sizeResolver]
 * set, one will be set on the request using the layout constraints.
 * @param modifier [Modifier] used to adjust the layout algorithm or draw decoration content.
 * @param alignment Optional alignment parameter used to place the loaded [ImageAsset] in the
 * given bounds defined by the width and height.
 * @param contentScale Optional scale parameter used to determine the aspect ratio scaling to be
 * used if the bounds are a different size from the intrinsic size of the loaded [ImageAsset].
 * @param colorFilter Optional colorFilter to apply for the [Painter] when it is rendered onscreen.
 * @param getSuccessPainter Optional builder for the [Painter] to be used to draw the successful
 * loading result. Passing in `null` will result in falling back to the default [Painter].
 * @param getFailurePainter Optional builder for the [Painter] to be used to draw the failure
 * loading result. Passing in `null` will result in falling back to the default [Painter].
 * @param loading Content to be displayed when the request is in progress.
 * @param shouldRefetchOnSizeChange Lambda which will be invoked when the size changes, allowing
 * optional re-fetching of the image. Return true to re-fetch the image.
 * @param onRequestCompleted Listener which will be called when the loading request has finished.
 */
@Composable
fun CoilImage(
    request: ImageRequest,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    getSuccessPainter: @Composable ((SuccessResult) -> Painter)? = null,
    getFailurePainter: @Composable ((ErrorResult) -> Painter?)? = null,
    loading: @Composable (() -> Unit)? = null,
    shouldRefetchOnSizeChange: (currentResult: RequestResult, size: IntSize) -> Boolean = defaultRefetchOnSizeChangeLambda,
    onRequestCompleted: (RequestResult) -> Unit = emptySuccessLambda
) {
    var result by stateFor<RequestResult?>(request) { null }

    // This may look a little weird, but allows the launchInComposition callback to always
    // invoke the last provided [onRequestCompleted].
    //
    // If a composition happens *after* launchInComposition has launched, the given
    // [onRequestCompleted] might have changed. If the actor lambda below directly referenced
    // [onRequestCompleted] it would have captured access to the initial onRequestCompleted
    // value, not the latest.
    //
    // This `callback` state enables the actor lambda to only capture the remembered state
    // reference, which we can update on each composition.
    val callback = remember { mutableStateOf(onRequestCompleted, referentialEqualityPolicy()) }
    callback.value = onRequestCompleted

    val requestActor = remember(request) { CoilRequestActor(request) }

    launchInComposition(requestActor) {
        // Launch the Actor
        requestActor.run { _, actorResult ->
            // Update the result state
            result = actorResult

            if (actorResult != null) {
                // Execute the onRequestCompleted callback if we have a new result
                callback.value(actorResult)
            }
        }
    }

    val painter = when (val r = result) {
        is SuccessResult -> {
            if (getSuccessPainter != null) {
                getSuccessPainter(r)
            } else {
                defaultSuccessPainterGetter(r)
            }
        }
        is ErrorResult -> {
            if (getFailurePainter != null) {
                getFailurePainter(r)
            } else {
                defaultFailurePainterGetter(r)
            }
        }
        else -> null
    }

    WithConstraints(modifier) {
        // We remember the last size in a MutableRef (below) rather than a MutableState.
        // This is because we don't need value changes to trigger a re-composition, we are only
        // using it to store the last value.
        val lastRequestedSize = remember(requestActor) { MutableRef(IntSize.Zero) }

        val requestSize = IntSize(
            width = if (constraints.hasBoundedWidth) constraints.maxWidth else UNSPECIFIED,
            height = if (constraints.hasBoundedHeight) constraints.maxHeight else UNSPECIFIED
        )

        val r = result
        if (lastRequestedSize.value != requestSize &&
            (r == null || shouldRefetchOnSizeChange(r, requestSize))
        ) {
            requestActor.send(requestSize)
            lastRequestedSize.value = requestSize
        }

        if (painter == null) {
            // If we don't have a result painter, we add a Box...
            Box {
                // If we don't have a result yet, we can show the loading content
                // (if not null)
                if (result == null && loading != null) {
                    loading()
                }
            }
        } else {
            Image(
                painter = painter,
                contentScale = contentScale,
                alignment = alignment,
                colorFilter = colorFilter,
            )
        }
    }
}

/**
 * Value for a [IntSize] dimension, where the dimension is not specified or is unknown.
 */
private const val UNSPECIFIED = -1

@Stable
private data class MutableRef<T>(var value: T)

private fun CoilRequestActor(
    request: ImageRequest
) = RequestActor<IntSize, RequestResult?> { size ->
    when {
        request.defined.sizeResolver != null -> {
            // If the request has a size resolver set we just execute the request as-is
            request
        }
        size.width == UNSPECIFIED || size.height == UNSPECIFIED -> {
            // If the size contains an unspecified dimension, we don't specify a size
            // in the Coil request
            request
        }
        size != IntSize.Zero -> {
            // If we have a non-zero size, we can modify the request to include the size
            request.newBuilder().size(size.width, size.height).build()
        }
        else -> {
            // Otherwise we have a zero size, so no point executing a request
            null
        }
    }?.let { transformedRequest ->
        // Now execute the request in Coil...
        Coil.imageLoader(transformedRequest.context)
            .execute(transformedRequest)
            .toResult(size)
            .also {
                // Tell RenderThread to pre-upload this bitmap. Saves the GPU upload cost on the
                // first draw. See https://github.com/square/picasso/issues/1620 for a explanation
                // from @ChrisCraik
                it.image?.prepareToDraw()
            }
    }
}

/**
 * Represents the result of an image request.
 */
sealed class RequestResult {
    abstract val image: ImageAsset?
}

/**
 * Indicates that the request completed successfully.
 *
 * @param image The result image.
 * @param source The data source that the image was loaded from.
 */
data class SuccessResult(
    override val image: ImageAsset,
    val source: DataSource
) : RequestResult() {
    internal constructor(result: coil.request.SuccessResult, fallbackSize: IntSize) : this(
        image = result.drawable.toImageAsset(fallbackSize),
        source = result.metadata.dataSource
    )
}

/**
 * Indicates that an error occurred while executing the request.
 *
 * @param image The error image.
 * @param throwable The error that failed the request.
 */
data class ErrorResult(
    override val image: ImageAsset?,
    val throwable: Throwable
) : RequestResult() {
    internal constructor(result: coil.request.ErrorResult, fallbackSize: IntSize) : this(
        image = result.drawable?.toImageAsset(fallbackSize),
        throwable = result.throwable
    )
}

private fun ImageResult.toResult(
    fallbackSize: IntSize = IntSize.Zero
): RequestResult = when (this) {
    is coil.request.SuccessResult -> SuccessResult(this, fallbackSize)
    is coil.request.ErrorResult -> ErrorResult(this, fallbackSize)
}

@Composable
internal fun defaultFailurePainterGetter(error: ErrorResult): Painter? {
    return error.image?.let { image ->
        remember(image) { ImagePainter(image) }
    }
}

@Composable
internal fun defaultSuccessPainterGetter(result: SuccessResult): Painter {
    return remember(result.image) { ImagePainter(result.image) }
}

internal val emptySuccessLambda: (RequestResult) -> Unit = {}

internal val defaultRefetchOnSizeChangeLambda: (RequestResult, IntSize) -> Boolean = { _, _ -> false }

internal fun Drawable.toImageAsset(fallbackSize: IntSize = IntSize.Zero): ImageAsset {
    return toBitmap(
        width = if (intrinsicWidth > 0) intrinsicWidth else fallbackSize.width,
        height = if (intrinsicHeight > 0) intrinsicHeight else fallbackSize.height
    ).asImageAsset()
}
