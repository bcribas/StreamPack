/*
 * Copyright (C) 2026 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.video.camera

/**
 * A source that holds camera2 devices open without being a [CameraSource] itself.
 *
 * camera2 allows only one client per device, and
 * [io.github.thibaultbee.streampack.core.pipelines.inputs.VideoInput] enforces that by releasing
 * the previous source before opening a new camera — but it recognises only a direct
 * [CameraSource]. A source that owns cameras indirectly, such as a composition with a camera
 * layer, announces them here so the same rule applies to it.
 */
interface ICameraHoldingSource {
    /**
     * The camera2 ids this source currently holds open.
     */
    val heldCameraIds: Set<String>

    /**
     * Stops and releases only the camera devices, leaving everything else alone.
     *
     * Called immediately before a competing source opens one of [heldCameraIds].
     */
    suspend fun evictCameras()
}
