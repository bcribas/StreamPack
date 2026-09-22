/*
 * Copyright 2022 The Android Open Source Project
 * Copyright 2024 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.util.Pair
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.EMPTY_ATTRIBS
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.IDENTITY_MATRIX
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.InputFormat
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.NO_OUTPUT_SURFACE
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.PIXEL_STRIDE
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.Program2D
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.SamplerShaderProgram
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkEglErrorOrLog
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkEglErrorOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkGlErrorOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkGlThreadOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.checkInitializedOrThrow
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.chooseSurfaceAttrib
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createPBufferSurface
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createPrograms
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createTexture
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.createWindowSurface
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.deleteFbo
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.deleteTexture
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.generateFbo
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.generateTexture
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.getSurfaceSize
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils.glVersionNumber
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GraphicDeviceInfo
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.OutputSurface
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.logger.Logger
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGL10

/**
 * OpenGLRenderer renders texture image to the output surface.
 *
 *
 * OpenGLRenderer's methods must run on the same thread, so called GL thread. The GL thread is
 * locked as the thread running the [.init] method, otherwise an
 * [IllegalStateException] will be thrown when other methods are called.
 */
@WorkerThread
class OpenGlRenderer {
    protected val mInitialized: AtomicBoolean = AtomicBoolean(false)
    protected val mOutputSurfaceMap: MutableMap<Surface, OutputSurface> =
        HashMap()
    protected val mViewportRectMap: MutableMap<Surface, Rect> =
        HashMap()
    protected var mGlThread: Thread? = null
    protected var mEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    protected var mEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    protected var mSurfaceAttrib: IntArray = EMPTY_ATTRIBS
    protected var mEglConfig: EGLConfig? = null
    protected var mTempSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    protected var mCurrentSurface: Surface? = null
    protected var mProgramHandles: Map<InputFormat, Program2D> = emptyMap()
    protected var mCurrentProgram: Program2D? = null
    protected var mCurrentInputformat: InputFormat = InputFormat.UNKNOWN

    private var mExternalTextureId = -1

    /**
     * Extra `GL_TEXTURE_EXTERNAL_OES` textures handed out by [createInputTexture], one per
     * composited input. [mExternalTextureId] is not part of this set.
     */
    private val mOwnedTextures = mutableSetOf<Int>()

    /**
     * Last GL state uploaded by [drawLayer], so the common single-layer case does not re-bind the
     * texture or re-upload uniforms on every frame. Invalidated whenever the program changes,
     * because [Program2D.use] resets both uniforms.
     */
    private var mBoundTextureId = -1
    private var mUploadedAlpha = Float.NaN
    private val mUploadedTransMatrix = FloatArray(16)
    private var mHasUploadedTransMatrix = false

    /**
     * Initializes the OpenGLRenderer
     *
     *
     * This is equivalent to calling [.init] without providing any
     * shader overrides. Default shaders will be used for the dynamic range specified.
     */
    fun init(dynamicRange: DynamicRangeProfile): GraphicDeviceInfo {
        return init(dynamicRange, emptyMap<InputFormat?, ShaderProvider>())
    }

    /**
     * Initializes the OpenGLRenderer
     *
     *
     * Initialization must be done before calling other methods, otherwise an
     * [IllegalStateException] will be thrown. Following methods must run on the same
     * thread as this method, so called GL thread, otherwise an [IllegalStateException]
     * will be thrown.
     *
     * @param dynamicRange    the dynamic range used to select default shaders.
     * @param shaderOverrides specific shader overrides for fragment shaders
     * per [InputFormat].
     * @return Info about the initialized graphics device.
     * @throws IllegalStateException    if the renderer is already initialized or failed to be
     * initialized.
     * @throws IllegalArgumentException if the ShaderProvider fails to create shader or provides
     * invalid shader string.
     */
    fun init(
        dynamicRange: DynamicRangeProfile,
        shaderOverrides: Map<InputFormat?, ShaderProvider?>
    ): GraphicDeviceInfo {
        checkInitializedOrThrow(mInitialized, false)
        val infoBuilder = GraphicDeviceInfo.Builder()
        try {
            var dynamicRangeCorrected = dynamicRange
            if (dynamicRange.isHdr) {
                val extensions = getExtensionsBeforeInitialized(dynamicRange)
                val glExtensions = requireNotNull(extensions.first)
                val eglExtensions = requireNotNull(extensions.second)
                if (!glExtensions.contains("GL_EXT_YUV_target")) {
                    Logger.w(TAG, "Device does not support GL_EXT_YUV_target. Fallback to SDR.")
                    dynamicRangeCorrected = DynamicRangeProfile.sdr
                }
                mSurfaceAttrib = chooseSurfaceAttrib(eglExtensions, dynamicRangeCorrected)
                infoBuilder.setGlExtensions(glExtensions)
                infoBuilder.setEglExtensions(eglExtensions)
            }
            createEglContext(dynamicRangeCorrected, infoBuilder)
            createTempSurface()
            makeCurrent(mTempSurface)
            infoBuilder.setGlVersion(glVersionNumber)
            mProgramHandles = createPrograms(dynamicRangeCorrected, shaderOverrides)
            mExternalTextureId = createTexture()
            useAndConfigureProgramWithTexture(mExternalTextureId)
        } catch (e: IllegalStateException) {
            releaseInternal()
            throw e
        } catch (e: IllegalArgumentException) {
            releaseInternal()
            throw e
        }
        mGlThread = Thread.currentThread()
        mInitialized.set(true)
        return infoBuilder.build()
    }

    /**
     * Releases the OpenGLRenderer
     *
     * @throws IllegalStateException if the caller doesn't run on the GL thread.
     */
    fun release() {
        if (!mInitialized.getAndSet(false)) {
            return
        }
        checkGlThreadOrThrow(mGlThread)
        releaseInternal()
    }

    /**
     * Register the output surface.
     *
     * @param surface The output surface to register.
     * @param viewportRect The viewport rect for letterboxing/pillarboxing. If null, full surface will be used.
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun registerOutputSurface(surface: Surface, viewportRect: Rect? = null) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        if (!mOutputSurfaceMap.containsKey(surface)) {
            mOutputSurfaceMap[surface] = NO_OUTPUT_SURFACE
            if (viewportRect != null) {
                mViewportRectMap[surface] = viewportRect
            }
        }
    }

    /**
     * Unregister the output surface.
     *
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun unregisterOutputSurface(surface: Surface) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        removeOutputSurfaceInternal(surface, true)
    }

    val textureName: Int
        /**
         * Gets the texture name.
         *
         * @return the texture name
         * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
         * on the GL thread.
         */
        get() {
            checkInitializedOrThrow(mInitialized, true)
            checkGlThreadOrThrow(mGlThread)

            return mExternalTextureId
        }

    /**
     * Creates an additional `GL_TEXTURE_EXTERNAL_OES` texture, owned by this renderer.
     *
     * [textureName] returns a single shared texture, which is enough for a one-input processor
     * but makes several inputs overwrite each other. A compositing processor calls this once per
     * input instead, so each [android.graphics.SurfaceTexture] gets its own texture.
     *
     * @return the new texture name, to be released with [deleteInputTexture].
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun createInputTexture(): Int {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        val textureId = createTexture()
        mOwnedTextures.add(textureId)

        // createTexture() leaves its own texture bound: restore the default binding.
        activateExternalTexture(mExternalTextureId)

        return textureId
    }

    /**
     * Deletes a texture created by [createInputTexture]. Unknown textures are ignored.
     *
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun deleteInputTexture(textureId: Int) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        if (mOwnedTextures.remove(textureId)) {
            deleteTexture(textureId)
            activateExternalTexture(mExternalTextureId)
        }
    }

    /**
     * Sets the input format.
     *
     *
     * This will ensure the correct sampler is used for the input.
     *
     * @param inputFormat The input format for the input texture.
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun setInputFormat(inputFormat: InputFormat) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        if (mCurrentInputformat !== inputFormat) {
            mCurrentInputformat = inputFormat
            useAndConfigureProgramWithTexture(mExternalTextureId)
        }
    }

    private fun activateExternalTexture(externalTextureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlErrorOrThrow("glActiveTexture")

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        checkGlErrorOrThrow("glBindTexture")

        mBoundTextureId = externalTextureId
    }

    /**
     * Starts a frame on [surface]: makes its EGL surface current, sets the viewport and
     * optionally clears the whole surface.
     *
     * Must be paired with [endFrame]. Between the two, call [drawLayer] once per layer.
     *
     * @param surface the output surface, previously registered by [registerOutputSurface].
     * @param viewportRect the viewport to draw into. When `null`, the surface's registered
     * viewport is used (letterboxing/pillarboxing).
     * @param clearColor an ARGB color to clear the whole surface with, or `null` to skip the
     * clear. Clearing is what keeps the area outside [viewportRect] from showing stale buffer
     * content.
     * @return `false` when the surface could not be created and nothing should be drawn.
     * @throws IllegalStateException if the renderer is not initialized, the caller doesn't run
     * on the GL thread or the surface is not registered by [registerOutputSurface].
     */
    fun beginFrame(
        surface: Surface,
        viewportRect: Rect? = null,
        clearColor: Int? = Color.BLACK
    ): Boolean {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        var outputSurface: OutputSurface? = getOutSurfaceOrThrow(surface)

        // Workaround situations that out surface is failed to create or needs to be recreated.
        if (outputSurface === NO_OUTPUT_SURFACE) {
            outputSurface = createOutputSurfaceInternal(surface)
            if (outputSurface == null) {
                return false
            }

            mOutputSurfaceMap[surface] = outputSurface
        }

        requireNotNull(outputSurface)

        // Set output surface.
        if (surface !== mCurrentSurface) {
            makeCurrent(outputSurface.eglSurface)
            mCurrentSurface = surface
        }

        // The viewport is set on every frame on purpose: the snapshot path also calls
        // glViewport and other callers may share this context, so caching it on surface
        // changes alone is not safe.
        val viewport = viewportRect ?: outputSurface.viewPortRect
        GLES20.glViewport(
            viewport.left,
            viewport.top,
            viewport.width(),
            viewport.height()
        )

        if (clearColor != null) {
            // The clear covers the whole surface, not just the viewport: GL_SCISSOR_TEST is
            // never enabled, so without it the letterbox/pillarbox bars outside the viewport
            // keep whatever the driver left in the buffer.
            GLES20.glClearColor(
                Color.red(clearColor) / 255f,
                Color.green(clearColor) / 255f,
                Color.blue(clearColor) / 255f,
                Color.alpha(clearColor) / 255f
            )
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        return true
    }

    /**
     * Draws a single textured quad into the frame started by [beginFrame].
     *
     * @param externalTextureId the `GL_TEXTURE_EXTERNAL_OES` texture to sample.
     * @param textureTransform the texture transform matrix (`uTexMatrix`).
     * @param transformMatrix the geometry matrix (`uTransMatrix`) placing the quad. Identity
     * fills the whole viewport.
     * @param alpha the layer opacity, applied through `uAlphaScale`.
     * @param blend whether to blend this layer over what is already drawn.
     * @throws IllegalStateException if the renderer is not initialized or the caller doesn't run
     * on the GL thread.
     */
    fun drawLayer(
        externalTextureId: Int,
        textureTransform: FloatArray,
        transformMatrix: FloatArray = IDENTITY_MATRIX,
        alpha: Float = 1f,
        blend: Boolean = false
    ) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        if (blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        if (externalTextureId != mBoundTextureId) {
            activateExternalTexture(externalTextureId)
        }

        val program: Program2D = requireNotNull(mCurrentProgram)
        if (program is SamplerShaderProgram) {
            // TODO(b/245855601): Upload the matrix to GPU when textureTransform is changed.
            program.updateTextureMatrix(textureTransform)
        }

        // Program2D.use() resets the transform matrix and the alpha, and it only runs when the
        // program changes, so neither uniform can be assumed to hold. They are cached rather
        // than re-uploaded blindly: a single full-frame layer then costs no extra GL call
        // compared to the pre-compositing renderer.
        if (!mHasUploadedTransMatrix || !mUploadedTransMatrix.contentEquals(transformMatrix)) {
            program.updateTransformMatrix(transformMatrix)
            transformMatrix.copyInto(mUploadedTransMatrix)
            mHasUploadedTransMatrix = true
        }
        if (alpha != mUploadedAlpha) {
            program.updateAlpha(alpha)
            mUploadedAlpha = alpha
        }

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  /*firstVertex=*/0,  /*vertexCount=*/4)
        checkGlErrorOrThrow("glDrawArrays")

        if (blend) {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    /**
     * Timestamps the frame started by [beginFrame] and swaps it to [surface].
     *
     * @throws IllegalStateException if the renderer is not initialized, the caller doesn't run
     * on the GL thread or the surface is not registered by [registerOutputSurface].
     */
    fun endFrame(surface: Surface, timestampNs: Long) {
        checkInitializedOrThrow(mInitialized, true)
        checkGlThreadOrThrow(mGlThread)

        val outputSurface = getOutSurfaceOrThrow(surface)
        if (outputSurface === NO_OUTPUT_SURFACE) {
            return
        }

        // Set timestamp
        EGLExt.eglPresentationTimeANDROID(mEglDisplay, outputSurface.eglSurface, timestampNs)

        // Swap buffer
        if (!EGL14.eglSwapBuffers(mEglDisplay, outputSurface.eglSurface)) {
            Logger.w(
                TAG, "Failed to swap buffers with EGL error: 0x" + Integer.toHexString(
                    EGL14.eglGetError()
                )
            )
            removeOutputSurfaceInternal(surface, false)
        }
    }

    /**
     * Renders the texture image to the output surface.
     *
     * Equivalent to [beginFrame] + a single full-viewport [drawLayer] + [endFrame].
     *
     * @throws IllegalStateException if the renderer is not initialized, the caller doesn't run
     * on the GL thread or the surface is not registered by
     * [.registerOutputSurface].
     */
    fun render(
        timestampNs: Long,
        textureTransform: FloatArray,
        surface: Surface,
        isMuted: Boolean = false
    ) {
        if (!beginFrame(surface, clearColor = Color.BLACK)) {
            return
        }

        if (!isMuted) {
            drawLayer(mExternalTextureId, textureTransform)
        }

        endFrame(surface, timestampNs)
    }

    /**
     * Takes a snapshot of the current external texture and returns a Bitmap.
     *
     * @param size             the size of the output [Bitmap].
     * @param textureTransform the transformation matrix.
     * See: [SurfaceOutput.updateTransformMatrix]
     */
    fun snapshot(size: Size, textureTransform: FloatArray): Bitmap {
        // Allocate buffer.
        val byteBuffer = ByteBuffer.allocateDirect(
            size.width * size.height * PIXEL_STRIDE
        )

        // Take a snapshot.
        snapshot(byteBuffer, size, textureTransform)
        byteBuffer.rewind()

        // Create a Bitmap and copy the bytes over.
        val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(byteBuffer)
        return bitmap
    }

    /**
     * Takes a snapshot of the current external texture and stores it in the given byte buffer.
     *
     *
     *  The image is stored as RGBA with pixel stride of 4 bytes and row stride of width * 4
     * bytes.
     *
     * @param byteBuffer       the byte buffer to store the snapshot.
     * @param size             the size of the output image.
     * @param textureTransform the transformation matrix.
     * See: [SurfaceOutput.updateTransformMatrix]
     */
    private fun snapshot(
        byteBuffer: ByteBuffer, size: Size,
        textureTransform: FloatArray
    ) {
        check(byteBuffer.capacity() == size.width * size.height * 4) {
            "ByteBuffer capacity is not equal to width * height * 4."
        }
        check(byteBuffer.isDirect) { "ByteBuffer is not direct." }

        // Create and initialize intermediate texture.
        val texture: Int = generateTexture()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        checkGlErrorOrThrow("glBindTexture")
        // Configure the texture.
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, size.width,
            size.height, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null
        )
        checkGlErrorOrThrow("glTexImage2D")
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )

        // Create FBO.
        val fbo: Int = generateFbo()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        checkGlErrorOrThrow("glBindFramebuffer")

        // Attach the intermediate texture to the FBO
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0
        )
        checkGlErrorOrThrow("glFramebufferTexture2D")

        // Bind external texture (camera output).
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        checkGlErrorOrThrow("glActiveTexture")
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mExternalTextureId)
        checkGlErrorOrThrow("glBindTexture")

        // Set scissor and viewport.
        mCurrentSurface = null
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glScissor(0, 0, size.width, size.height)

        val program: Program2D = requireNotNull(mCurrentProgram)
        if (program is SamplerShaderProgram) {
            // Upload transform matrix.
            program.updateTextureMatrix(textureTransform)
        }

        // Draw the external texture to the intermediate texture.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,  /*firstVertex=*/0,  /*vertexCount=*/4)
        checkGlErrorOrThrow("glDrawArrays")

        // Read the pixels from the framebuffer
        GLES20.glReadPixels(
            0, 0, size.width, size.height, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            byteBuffer
        )
        checkGlErrorOrThrow("glReadPixels")

        // Clean up
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        deleteTexture(texture)
        deleteFbo(fbo)
        // Set the external texture to be active.
        activateExternalTexture(mExternalTextureId)
        // snapshot() uploaded its own texture matrix and moved the viewport.
        mHasUploadedTransMatrix = false
        mUploadedAlpha = Float.NaN
    }

    // Returns a pair of GL extension (first) and EGL extension (second) strings.
    private fun getExtensionsBeforeInitialized(
        dynamicRangeToInitialize: DynamicRangeProfile
    ): Pair<String, String> {
        checkInitializedOrThrow(mInitialized, false)
        try {
            createEglContext(dynamicRangeToInitialize,  /*infoBuilder=*/null)
            createTempSurface()
            makeCurrent(mTempSurface)
            // eglMakeCurrent() has to be called before checking GL_EXTENSIONS.
            val glExtensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            val eglExtensions = EGL14.eglQueryString(mEglDisplay, EGL14.EGL_EXTENSIONS)
            return Pair(
                glExtensions ?: "", eglExtensions ?: ""
            )
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Failed to get GL or EGL extensions: " + e.message, e)
            return Pair("", "")
        } finally {
            releaseInternal()
        }
    }

    private fun createEglContext(
        dynamicRange: DynamicRangeProfile,
        infoBuilder: GraphicDeviceInfo.Builder?
    ) {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(mEglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to get EGL14 display" }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            mEglDisplay = EGL14.EGL_NO_DISPLAY
            throw IllegalStateException("Unable to initialize EGL14")
        }

        infoBuilder?.setEglVersion(version[0].toString() + "." + version[1])

        val rgbBits = if (dynamicRange.isHdr) 10 else 8
        val alphaBits = if (dynamicRange.isHdr) 2 else 8
        val renderType = if (dynamicRange.isHdr)
            EGLExt.EGL_OPENGL_ES3_BIT_KHR
        else
            EGL14.EGL_OPENGL_ES2_BIT
        // TODO(b/319277249): It will crash on older Samsung devices for HDR video 10-bit
        //  because EGLExt.EGL_RECORDABLE_ANDROID is only supported from OneUI 6.1. We need to
        //  check by GPU Driver version when new OS is release.
        val recordableAndroid =
            if (dynamicRange.isHdr) EGL10.EGL_DONT_CARE else EGL14.EGL_TRUE
        val attribToChooseConfig = intArrayOf(
            EGL14.EGL_RED_SIZE, rgbBits,
            EGL14.EGL_GREEN_SIZE, rgbBits,
            EGL14.EGL_BLUE_SIZE, rgbBits,
            EGL14.EGL_ALPHA_SIZE, alphaBits,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, renderType,
            EGLExt.EGL_RECORDABLE_ANDROID, recordableAndroid,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                mEglDisplay, attribToChooseConfig, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) { "Unable to find a suitable EGLConfig" }
        val config = configs[0]
        val attribToCreateContext = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, if (dynamicRange.isHdr) 3 else 2,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            mEglDisplay, config, EGL14.EGL_NO_CONTEXT,
            attribToCreateContext, 0
        )
        checkEglErrorOrThrow("eglCreateContext")
        mEglConfig = config
        mEglContext = context

        // Confirm with query.
        val values = IntArray(1)
        EGL14.eglQueryContext(
            mEglDisplay, mEglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values,
            0
        )
        Log.d(TAG, "EGLContext created, client version " + values[0])
    }

    private fun createTempSurface() {
        mTempSurface = createPBufferSurface(
            mEglDisplay, requireNotNull(mEglConfig),  /*width=*/1,  /*height=*/
            1
        )
    }

    protected fun makeCurrent(eglSurface: EGLSurface) {
        check(
            EGL14.eglMakeCurrent(
                mEglDisplay,
                eglSurface,
                eglSurface,
                mEglContext
            )
        ) { "eglMakeCurrent failed" }
    }

    protected fun useAndConfigureProgramWithTexture(textureId: Int) {
        val program = requireNotNull(mProgramHandles[mCurrentInputformat]) {
            "Unable to configure program for input format: $mCurrentInputformat"
        }
        if (mCurrentProgram !== program) {
            mCurrentProgram = program
            program.use()
            // use() resets uTransMatrix to identity and uAlphaScale to 1.0.
            mHasUploadedTransMatrix = false
            mUploadedAlpha = Float.NaN
            Log.d(
                TAG, ("Using program for input format " + mCurrentInputformat + ": "
                        + mCurrentProgram)
            )
        }

        // Activate the texture
        activateExternalTexture(textureId)
    }

    private fun releaseInternal() {
        // Delete textures handed out by createInputTexture
        for (textureId in mOwnedTextures) {
            try {
                deleteTexture(textureId)
            } catch (e: RuntimeException) {
                Logger.w(TAG, "Failed to delete input texture $textureId: ${e.message}", e)
            }
        }
        mOwnedTextures.clear()

        // Delete program
        for (program in mProgramHandles.values) {
            program.delete()
        }
        mProgramHandles = emptyMap<InputFormat, Program2D>()
        mCurrentProgram = null

        if (mEglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )

            // Destroy EGLSurfaces
            for (outputSurface in mOutputSurfaceMap.values) {
                if (outputSurface.eglSurface != EGL14.EGL_NO_SURFACE) {
                    if (!EGL14.eglDestroySurface(mEglDisplay, outputSurface.eglSurface)) {
                        checkEglErrorOrLog("eglDestroySurface")
                    }
                }
            }
            mOutputSurfaceMap.clear()

            // Destroy temp surface
            if (mTempSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEglDisplay, mTempSurface)
                mTempSurface = EGL14.EGL_NO_SURFACE
            }

            // Destroy EGLContext and terminate display
            if (mEglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext)
                mEglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEglDisplay)
            mEglDisplay = EGL14.EGL_NO_DISPLAY
        }

        // Reset other members
        mEglConfig = null
        mExternalTextureId = -1
        mBoundTextureId = -1
        mUploadedAlpha = Float.NaN
        mHasUploadedTransMatrix = false
        mCurrentInputformat = InputFormat.UNKNOWN
        mCurrentSurface = null
        mGlThread = null
    }

    protected fun getOutSurfaceOrThrow(surface: Surface): OutputSurface {
        check(mOutputSurfaceMap.containsKey(surface)) {
            "The surface is not registered."
        }

        return requireNotNull(mOutputSurfaceMap[surface])
    }

    protected fun createOutputSurfaceInternal(
        surface: Surface
    ): OutputSurface? {
        val eglSurface = try {
            createWindowSurface(
                mEglDisplay, requireNotNull(mEglConfig), surface,
                mSurfaceAttrib
            )
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Failed to create EGL surface: " + e.message, e)
            return null
        } catch (e: IllegalArgumentException) {
            Logger.w(TAG, "Failed to create EGL surface: " + e.message, e)
            return null
        }
        val size = getSurfaceSize(mEglDisplay, eglSurface)
        // Use stored viewport rect if available, otherwise use full surface
        val viewportRect = mViewportRectMap[surface] ?: Rect(0, 0, size.width, size.height)
        return OutputSurface(eglSurface, viewportRect)
    }

    protected fun removeOutputSurfaceInternal(surface: Surface, unregister: Boolean) {
        // Unmake current surface.
        if (mCurrentSurface === surface) {
            mCurrentSurface = null
            makeCurrent(mTempSurface)
        }

        // Remove cached EGL surface and viewport rect.
        val removedOutputSurface: OutputSurface = if (unregister) {
            mOutputSurfaceMap.remove(surface)!!.also {
                mViewportRectMap.remove(surface)
            }
        } else {
            mOutputSurfaceMap.put(surface, NO_OUTPUT_SURFACE)!!
        }

        // Destroy EGL surface.
        if (removedOutputSurface !== NO_OUTPUT_SURFACE) {
            try {
                EGL14.eglDestroySurface(mEglDisplay, removedOutputSurface.eglSurface)
            } catch (e: RuntimeException) {
                Logger.w(TAG, "Failed to destroy EGL surface: " + e.message, e)
            }
        }
    }

    companion object {
        private const val TAG = "OpenGlRenderer"
    }
}