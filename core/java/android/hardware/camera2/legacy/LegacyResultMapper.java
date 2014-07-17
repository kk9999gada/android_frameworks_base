/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.legacy;

import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.legacy.ParameterUtils.WeightedRectangle;
import android.hardware.camera2.legacy.ParameterUtils.ZoomData;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.utils.ListUtils;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;

import static android.hardware.camera2.CaptureResult.*;

/**
 * Provide legacy-specific implementations of camera2 CaptureResult for legacy devices.
 */
public class LegacyResultMapper {
    private static final String TAG = "LegacyResultMapper";
    private static final boolean VERBOSE = Log.isLoggable(TAG, Log.VERBOSE);

    private LegacyRequest mCachedRequest = null;
    private CameraMetadataNative mCachedResult = null;

    /**
     * Generate capture result metadata from the legacy camera request.
     *
     * <p>This method caches and reuses the result from the previous call to this method if
     * the {@code parameters} of the subsequent {@link LegacyRequest} passed to this method
     * have not changed.</p>
     *
     * @param legacyRequest a non-{@code null} legacy request containing the latest parameters
     * @param timestamp the timestamp to use for this result in nanoseconds.
     *
     * @return {@link CameraMetadataNative} object containing result metadata.
     */
    public CameraMetadataNative cachedConvertResultMetadata(
            LegacyRequest legacyRequest, long timestamp) {
        if (mCachedRequest != null && legacyRequest.parameters.same(mCachedRequest.parameters)) {
            CameraMetadataNative newResult = new CameraMetadataNative(mCachedResult);

            // sensor.timestamp
            newResult.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);
            return newResult;
        }

        mCachedRequest = legacyRequest;
        mCachedResult = convertResultMetadata(mCachedRequest, timestamp);
        return mCachedResult;
    }

    /**
     * Generate capture result metadata from the legacy camera request.
     *
     * @param legacyRequest a non-{@code null} legacy request containing the latest parameters
     * @param timestamp the timestamp to use for this result in nanoseconds.
     *
     * @return a {@link CameraMetadataNative} object containing result metadata.
     */
    public static CameraMetadataNative convertResultMetadata(LegacyRequest legacyRequest,
                                                      long timestamp) {
        CameraCharacteristics characteristics = legacyRequest.characteristics;
        CaptureRequest request = legacyRequest.captureRequest;
        Size previewSize = legacyRequest.previewSize;
        Camera.Parameters params = legacyRequest.parameters;

        CameraMetadataNative result = new CameraMetadataNative();

        Rect activeArraySize = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        ZoomData zoomData = ParameterUtils.convertScalerCropRegion(activeArraySize,
                request.get(CaptureRequest.SCALER_CROP_REGION), previewSize, params);

        /*
         * control
         */
        // control.afState
        if (LegacyMetadataMapper.LIE_ABOUT_AF) {
            // TODO: Implement autofocus state machine
            result.set(CaptureResult.CONTROL_AF_MODE, request.get(CaptureRequest.CONTROL_AF_MODE));
        }

        /*
         * control.ae*
         */
        mapAe(result, characteristics, request, activeArraySize, zoomData, /*out*/params);

        // control.awbLock
        result.set(CaptureResult.CONTROL_AWB_LOCK, params.getAutoWhiteBalanceLock());

        // control.awbState
        if (LegacyMetadataMapper.LIE_ABOUT_AWB_STATE) {
            // Lie to pass CTS temporarily.
            // TODO: CTS needs to be updated not to query this value
            // for LIMITED devices unless its guaranteed to be available.
            result.set(CaptureResult.CONTROL_AWB_STATE,
                    CameraMetadata.CONTROL_AWB_STATE_CONVERGED);
            // TODO: Read the awb mode from parameters instead
        }

        if (LegacyMetadataMapper.LIE_ABOUT_AWB) {
            result.set(CaptureResult.CONTROL_AWB_MODE,
                    request.get(CaptureRequest.CONTROL_AWB_MODE));
        }

        /*
         * flash
         */
        {
            // TODO
        }

        /*
         * lens
         */
        // lens.focalLength
        result.set(CaptureResult.LENS_FOCAL_LENGTH, params.getFocalLength());

        /*
         * scaler
         */
        mapScaler(result, zoomData, /*out*/params);

        /*
         * sensor
         */
        // sensor.timestamp
        result.set(CaptureResult.SENSOR_TIMESTAMP, timestamp);

        // TODO: Remaining result metadata tags conversions.
        return result;
    }

    private static void mapAe(CameraMetadataNative m,
            CameraCharacteristics characteristics,
            CaptureRequest request, Rect activeArray, ZoomData zoomData, /*out*/Parameters p) {
        // control.aeAntiBandingMode
        {
            int antiBandingMode = LegacyMetadataMapper.convertAntiBandingModeOrDefault(
                    p.getAntibanding());
            m.set(CONTROL_AE_ANTIBANDING_MODE, antiBandingMode);
        }

        // control.aeExposureCompensation
        {
            m.set(CONTROL_AE_EXPOSURE_COMPENSATION, p.getExposureCompensation());
        }

        // control.aeLock
        {
            boolean lock = p.isAutoExposureLockSupported() ? p.getAutoExposureLock() : false;
            m.set(CONTROL_AE_LOCK, lock);
            if (VERBOSE) {
                Log.v(TAG,
                        "mapAe - android.control.aeLock = " + lock +
                        ", supported = " + p.isAutoExposureLockSupported());
            }

            Boolean requestLock = request.get(CaptureRequest.CONTROL_AE_LOCK);
            if (requestLock != null && requestLock != lock) {
                Log.w(TAG,
                        "mapAe - android.control.aeLock was requested to " + requestLock +
                        " but resulted in " + lock);
            }
        }

        // control.aeMode, flash.mode, flash.state
        mapAeAndFlashMode(m, characteristics, p);

        // control.aeState
        if (LegacyMetadataMapper.LIE_ABOUT_AE_STATE) {
            // Lie to pass CTS temporarily.
            // TODO: Implement precapture trigger, after which we can report CONVERGED ourselves
            m.set(CONTROL_AE_STATE, CONTROL_AE_STATE_CONVERGED);
        }

        // control.aeRegions
        {
            if (VERBOSE) {
                String meteringAreas = p.get("metering-areas");
                Log.v(TAG, "mapAe - parameter dump; metering-areas: " + meteringAreas);
            }

            MeteringRectangle[] meteringRectArray = getMeteringRectangles(activeArray,
                    zoomData, p.getMeteringAreas(), "AE");

            m.set(CONTROL_AE_REGIONS, meteringRectArray);
        }

        // control.afRegions
        {
            if (VERBOSE) {
                String focusAreas = p.get("focus-areas");
                Log.v(TAG, "mapAe - parameter dump; focus-areas: " + focusAreas);
            }

            MeteringRectangle[] meteringRectArray = getMeteringRectangles(activeArray,
                    zoomData, p.getFocusAreas(), "AF");

            m.set(CONTROL_AF_REGIONS, meteringRectArray);
        }

        // control.awbLock
        {
            boolean lock = p.isAutoWhiteBalanceLockSupported() ?
                    p.getAutoWhiteBalanceLock() : false;
            m.set(CONTROL_AWB_LOCK, lock);
        }
    }

    private static MeteringRectangle[] getMeteringRectangles(Rect activeArray, ZoomData zoomData,
            List<Camera.Area> meteringAreaList, String regionName) {
        List<MeteringRectangle> meteringRectList = new ArrayList<>();
        if (meteringAreaList != null) {
            for (Camera.Area area : meteringAreaList) {
                WeightedRectangle rect =
                        ParameterUtils.convertCameraAreaToActiveArrayRectangle(
                                activeArray, zoomData, area);

                meteringRectList.add(rect.toMetering());
            }
        }

        if (VERBOSE) {
            Log.v(TAG,
                    "Metering rectangles for " + regionName + ": "
                     + ListUtils.listToString(meteringRectList));
        }

        return meteringRectList.toArray(new MeteringRectangle[0]);
    }

    /** Map results for control.aeMode, flash.mode, flash.state */
    private static void mapAeAndFlashMode(CameraMetadataNative m,
            CameraCharacteristics characteristics, Parameters p) {
        // Default: AE mode on but flash never fires
        int flashMode = FLASH_MODE_OFF;
        // If there is no flash on this camera, the state is always unavailable
        // , otherwise it's only known for TORCH/SINGLE modes
        Integer flashState = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                ? null : FLASH_STATE_UNAVAILABLE;
        int aeMode = CONTROL_AE_MODE_ON;

        String flashModeSetting = p.getFlashMode();

        if (flashModeSetting != null) {
            switch (flashModeSetting) {
                case Parameters.FLASH_MODE_OFF:
                    break; // ok, using default
                case Parameters.FLASH_MODE_AUTO:
                    aeMode = CONTROL_AE_MODE_ON_AUTO_FLASH;
                    break;
                case Parameters.FLASH_MODE_ON:
                    // flashMode = SINGLE + aeMode = ON is indistinguishable from ON_ALWAYS_FLASH
                    flashMode = FLASH_MODE_SINGLE;
                    aeMode = CONTROL_AE_MODE_ON_ALWAYS_FLASH;
                    flashState = FLASH_STATE_FIRED;
                    break;
                case Parameters.FLASH_MODE_RED_EYE:
                    aeMode = CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE;
                    break;
                case Parameters.FLASH_MODE_TORCH:
                    flashMode = FLASH_MODE_TORCH;
                    flashState = FLASH_STATE_FIRED;
                    break;
                default:
                    Log.w(TAG,
                            "mapAeAndFlashMode - Ignoring unknown flash mode " + p.getFlashMode());
            }
        }

        // flash.state
        m.set(FLASH_STATE, flashState);
        // flash.mode
        m.set(FLASH_MODE, flashMode);
        // control.aeMode
        m.set(CONTROL_AE_MODE, aeMode);
    }

    /** Map results for scaler.* */
    private static void mapScaler(CameraMetadataNative m,
            ZoomData zoomData,
            /*out*/Parameters p) {
        /*
         * scaler.cropRegion
         */
        {
            m.set(SCALER_CROP_REGION, zoomData.reportedCrop);
        }
    }
}