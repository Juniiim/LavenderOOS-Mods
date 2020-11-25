package com.oneplus.faceunlock.camera;

import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.util.Size;
import com.oneplus.faceunlock.engine.FaceEngine;
import com.oneplus.faceunlock.utils.Log;
import com.oneplus.faceunlock.utils.OperationResult;
import com.oneplus.faceunlock.utils.ResultCallback;
import com.oneplus.faceunlock.utils.Token;
import java.util.ArrayList;
import java.util.List;

public abstract class CameraDevice {
    private static final String TAG = CameraDevice.class.getSimpleName();
    private Handler m_Handler = new Handler();
    private OpenParams m_OpenParams;
    private PendingAction m_PendingAction = PendingAction.EMPTY;
    private PendingStartInfo m_PendingStartInfo;
    private PendingStopInfo m_PendingStopInfo;
    private StartParams m_StartParams;
    private StartRunnable m_StartRunnable;
    private Token m_StartToken;
    private State m_State = State.NEW;
    private List<Size> m_SupportedPreviewSizes = new ArrayList();

    public interface CameraDisconnectedCallback {
        void onCameraDisconnected();
    }

    public interface CameraErrorCallback {
        void onCameraError(int i);
    }

    public interface CameraFrameCallback {
        boolean onFrameReceived(Size size, int i, PreviewMetadata previewMetadata);
    }

    public interface FaceKeyPointsCallback {
        void onFaceKeyPointsUpdated(float[] fArr);
    }

    public enum LensFacing {
        BACK,
        FRONT
    }

    public static class OpenParams {
        public CameraDisconnectedCallback cameraDisconnectedCallback;
        public CameraErrorCallback cameraErrorCallback;
    }

    /* access modifiers changed from: private */
    public enum PendingAction {
        EMPTY,
        RELEASE,
        START,
        STOP
    }

    public static class StartParams {
        public CameraFrameCallback cameraFrameCallback;
        public int displayRotationDegrees;
        public FaceKeyPointsCallback faceKeyPointsCallback;
        public boolean isFixedSizeChanged;
        public boolean isFixedSizeChanging;
        public Size previewSize;
        public SurfaceTexture surfaceTexture;
    }

    /* access modifiers changed from: private */
    public enum State {
        INITIALIZED,
        INITIALIZING,
        NEW,
        RELEASED,
        RELEASING,
        STARTED,
        STARTING,
        STOPPED,
        STOPPING
    }

    public abstract byte[] dequeueCameraFrameBuffer();

    public abstract void enqueueCameraFrameBuffer(byte[] bArr);

    public abstract int getSensorOrientation();

    /* access modifiers changed from: protected */
    public abstract boolean onInitialize(OpenParams openParams, Handler handler, ResultCallback<OperationResult> resultCallback);

    /* access modifiers changed from: protected */
    public abstract boolean onRelease(boolean z, Handler handler, ResultCallback<OperationResult> resultCallback);

    /* access modifiers changed from: protected */
    public abstract boolean onStart(Token token, StartParams startParams, Handler handler, ResultCallback<OperationResult> resultCallback);

    /* access modifiers changed from: protected */
    public abstract boolean onStop(Token token, Handler handler, ResultCallback<OperationResult> resultCallback);

    /* access modifiers changed from: private */
    public class StartRunnable implements Runnable {
        private ResultCallback<OperationResult> callback;
        private StartParams params;

        public StartRunnable(StartParams params2, ResultCallback<OperationResult> callback2) {
            this.params = params2;
            this.callback = callback2;
        }

        public void run() {
            if (CameraDevice.this.m_StartRunnable == this) {
                CameraDevice.this.start(this.params, this.callback);
                CameraDevice.this.m_StartRunnable = null;
            }
        }
    }

    public class PreviewFrame {
        public final byte[] data;
        public final long timestamp;

        PreviewFrame(byte[] data2, long timestamp2) {
            this.data = data2;
            this.timestamp = timestamp2;
        }
    }

    public class PreviewMetadata {
        public final boolean isFaceDetected;
        public final long timestamp;

        PreviewMetadata(boolean isFaceDetected2, long timestamp2) {
            this.isFaceDetected = isFaceDetected2;
            this.timestamp = timestamp2;
        }
    }

    /* access modifiers changed from: private */
    public static class PendingStartInfo {
        final ResultCallback<OperationResult> callback;
        final StartParams params;

        PendingStartInfo(StartParams params2, ResultCallback<OperationResult> callback2) {
            this.params = params2;
            this.callback = callback2;
        }
    }

    /* access modifiers changed from: private */
    public static class PendingStopInfo {
        final ResultCallback<OperationResult> callback;

        PendingStopInfo(ResultCallback<OperationResult> callback2) {
            this.callback = callback2;
        }
    }

    protected CameraDevice(OpenParams params) {
        this.m_OpenParams = params;
        this.m_Handler.post(new Runnable() {
            /* class com.oneplus.faceunlock.camera.CameraDevice.AnonymousClass1 */

            public void run() {
                if (CameraDevice.this.m_State == State.NEW) {
                    CameraDevice.this.initialize(CameraDevice.this.m_OpenParams);
                }
            }
        });
    }

    private void changeState(State state) {
        if (this.m_State != state) {
            Log.v(TAG, "changeState() - [" + Integer.toHexString(hashCode()) + "] State : " + state);
            this.m_State = state;
        }
    }

    public static final CameraDevice create(OpenParams params) {
        if (useCamera2API()) {
            return new Camera2Device(params);
        }
        return new LegacyCameraDevice(params);
    }

    public static int findSensorOrientation() {
        if (useCamera2API()) {
            return Camera2Device.findSensorOrientation();
        }
        return LegacyCameraDevice.findSensorOrientation();
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private boolean initialize(OpenParams openParams) {
        if (this.m_State != State.NEW) {
            return true;
        }
        changeState(State.INITIALIZING);
        return onInitialize(openParams, this.m_Handler, new ResultCallback<OperationResult>() {
            /* class com.oneplus.faceunlock.camera.CameraDevice.AnonymousClass2 */

            public void onResult(Token token, OperationResult result) {
                CameraDevice.this.onInitialized(result);
            }
        });
    }

    public boolean isReady() {
        if (this.m_State == State.STARTED) {
            return true;
        }
        return false;
    }

    public boolean isRelease() {
        switch (this.m_State) {
            case RELEASING:
            case RELEASED:
                return true;
            default:
                return false;
        }
    }

    public boolean isStop() {
        switch (this.m_State) {
            case STOPPING:
            case STOPPED:
                return true;
            default:
                return false;
        }
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void onInitialized(OperationResult result) {
        verifyAccess();
        if (isRelease() || this.m_State != State.INITIALIZING) {
            return;
        }
        if (result == OperationResult.FAIL) {
            changeState(State.NEW);
            setPendingAction(PendingAction.EMPTY);
            if (this.m_PendingStartInfo != null) {
                if (this.m_PendingStartInfo.callback != null) {
                    this.m_PendingStartInfo.callback.onResult(null, OperationResult.FAIL);
                }
                this.m_PendingStartInfo = null;
                return;
            }
            return;
        }
        Log.v(TAG, "onInitialized()");
        changeState(State.INITIALIZED);
        switch (this.m_PendingAction) {
            case START:
                Log.v(TAG, "onInitialized() - Execute START pending action");
                StartParams params = null;
                ResultCallback<OperationResult> callback = null;
                if (this.m_PendingStartInfo != null) {
                    params = this.m_PendingStartInfo.params;
                    callback = this.m_PendingStartInfo.callback;
                    this.m_PendingStartInfo = null;
                }
                start(params, callback);
                break;
            case RELEASE:
                Log.v(TAG, "onInitialized() - Execute RELEASE pending action");
                release(null);
                break;
        }
        setPendingAction(PendingAction.EMPTY);
    }

    /* access modifiers changed from: protected */
    public void onPreviewFrameReceived(byte[] frame, PreviewMetadata metadata) {
        if (!isRelease()) {
            boolean isBufferHandled = false;
            if (this.m_StartParams.cameraFrameCallback != null) {
                isBufferHandled = this.m_StartParams.cameraFrameCallback.onFrameReceived(this.m_StartParams.previewSize, 17, metadata);
            }
            if (!isBufferHandled) {
                enqueueCameraFrameBuffer(dequeueCameraFrameBuffer());
            }
        }
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void onReleased(OperationResult result) {
        if (result == OperationResult.FAIL) {
            Log.w(TAG, "onRelease() - Fail to release camera");
        }
        changeState(State.RELEASED);
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void onStarted(Token token, OperationResult result) {
        verifyAccess();
        try {
            if (isRelease()) {
                Log.v(TAG, "onStarted() - Invalid state : " + this.m_State);
                switch (this.m_PendingAction) {
                    case RELEASE:
                        Log.v(TAG, "onStarted() - Execute RELEASE pending action");
                        release(null);
                        break;
                    case STOP:
                        Log.v(TAG, "onStarted() - Execute STOP pending action");
                        ResultCallback<OperationResult> callback = null;
                        if (this.m_PendingStopInfo != null) {
                            callback = this.m_PendingStopInfo.callback;
                            this.m_PendingStopInfo = null;
                        }
                        stop(callback);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            } else if (this.m_StartToken != token) {
                Log.w(TAG, "onStarted() - Token is updated, ignore");
                switch (this.m_PendingAction) {
                    case RELEASE:
                        Log.v(TAG, "onStarted() - Execute RELEASE pending action");
                        release(null);
                        break;
                    case STOP:
                        Log.v(TAG, "onStarted() - Execute STOP pending action");
                        ResultCallback<OperationResult> callback2 = null;
                        if (this.m_PendingStopInfo != null) {
                            callback2 = this.m_PendingStopInfo.callback;
                            this.m_PendingStopInfo = null;
                        }
                        stop(callback2);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            } else if (result == OperationResult.FAIL) {
                Log.v(TAG, "onStarted() - Fail to start : " + this.m_State);
                changeState(State.STOPPED);
                switch (this.m_PendingAction) {
                    case RELEASE:
                        Log.v(TAG, "onStarted() - Execute RELEASE pending action");
                        release(null);
                        break;
                    case STOP:
                        Log.v(TAG, "onStarted() - Execute STOP pending action");
                        ResultCallback<OperationResult> callback3 = null;
                        if (this.m_PendingStopInfo != null) {
                            callback3 = this.m_PendingStopInfo.callback;
                            this.m_PendingStopInfo = null;
                        }
                        stop(callback3);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            } else {
                Log.v(TAG, "onStarted()");
                changeState(State.STARTED);
                switch (this.m_PendingAction) {
                    case RELEASE:
                        Log.v(TAG, "onStarted() - Execute RELEASE pending action");
                        release(null);
                        break;
                    case STOP:
                        Log.v(TAG, "onStarted() - Execute STOP pending action");
                        ResultCallback<OperationResult> callback4 = null;
                        if (this.m_PendingStopInfo != null) {
                            callback4 = this.m_PendingStopInfo.callback;
                            this.m_PendingStopInfo = null;
                        }
                        stop(callback4);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            }
        } catch (Throwable th) {
            switch (this.m_PendingAction) {
                case RELEASE:
                    Log.v(TAG, "onStarted() - Execute RELEASE pending action");
                    release(null);
                    break;
                case STOP:
                    Log.v(TAG, "onStarted() - Execute STOP pending action");
                    ResultCallback<OperationResult> callback5 = null;
                    if (this.m_PendingStopInfo != null) {
                        callback5 = this.m_PendingStopInfo.callback;
                        this.m_PendingStopInfo = null;
                    }
                    stop(callback5);
                    break;
            }
            setPendingAction(PendingAction.EMPTY);
            throw th;
        }
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void onStopped(Token token, OperationResult result) {
        verifyAccess();
        try {
            if (isRelease()) {
                switch (this.m_PendingAction) {
                    case START:
                        Log.v(TAG, "onStopped() - Execute START pending action");
                        StartParams params = null;
                        ResultCallback<OperationResult> callback = null;
                        if (this.m_PendingStartInfo != null) {
                            params = this.m_PendingStartInfo.params;
                            callback = this.m_PendingStartInfo.callback;
                            this.m_PendingStartInfo = null;
                        }
                        start(params, callback);
                        break;
                    case RELEASE:
                        Log.v(TAG, "onStopped() - Execute RELEASE pending action");
                        release(null);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            } else if (this.m_StartToken != token) {
                Log.w(TAG, "onStopped() - Token is updated, ignore");
                switch (this.m_PendingAction) {
                    case START:
                        Log.v(TAG, "onStopped() - Execute START pending action");
                        StartParams params2 = null;
                        ResultCallback<OperationResult> callback2 = null;
                        if (this.m_PendingStartInfo != null) {
                            params2 = this.m_PendingStartInfo.params;
                            callback2 = this.m_PendingStartInfo.callback;
                            this.m_PendingStartInfo = null;
                        }
                        start(params2, callback2);
                        break;
                    case RELEASE:
                        Log.v(TAG, "onStopped() - Execute RELEASE pending action");
                        release(null);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            } else if (result == OperationResult.FAIL) {
                changeState(State.STARTED);
                switch (this.m_PendingAction) {
                    case START:
                        Log.v(TAG, "onStopped() - Execute START pending action");
                        StartParams params3 = null;
                        ResultCallback<OperationResult> callback3 = null;
                        if (this.m_PendingStartInfo != null) {
                            params3 = this.m_PendingStartInfo.params;
                            callback3 = this.m_PendingStartInfo.callback;
                            this.m_PendingStartInfo = null;
                        }
                        start(params3, callback3);
                        break;
                    case RELEASE:
                        Log.v(TAG, "onStopped() - Execute RELEASE pending action");
                        release(null);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            } else {
                Log.v(TAG, "onStopped()");
                changeState(State.STOPPED);
                switch (this.m_PendingAction) {
                    case START:
                        Log.v(TAG, "onStopped() - Execute START pending action");
                        StartParams params4 = null;
                        ResultCallback<OperationResult> callback4 = null;
                        if (this.m_PendingStartInfo != null) {
                            params4 = this.m_PendingStartInfo.params;
                            callback4 = this.m_PendingStartInfo.callback;
                            this.m_PendingStartInfo = null;
                        }
                        start(params4, callback4);
                        break;
                    case RELEASE:
                        Log.v(TAG, "onStopped() - Execute RELEASE pending action");
                        release(null);
                        break;
                }
                setPendingAction(PendingAction.EMPTY);
            }
        } catch (Throwable th) {
            switch (this.m_PendingAction) {
                case START:
                    Log.v(TAG, "onStopped() - Execute START pending action");
                    StartParams params5 = null;
                    ResultCallback<OperationResult> callback5 = null;
                    if (this.m_PendingStartInfo != null) {
                        params5 = this.m_PendingStartInfo.params;
                        callback5 = this.m_PendingStartInfo.callback;
                        this.m_PendingStartInfo = null;
                    }
                    start(params5, callback5);
                    break;
                case RELEASE:
                    Log.v(TAG, "onStopped() - Execute RELEASE pending action");
                    release(null);
                    break;
            }
            setPendingAction(PendingAction.EMPTY);
            throw th;
        }
    }

    public boolean release(ResultCallback<OperationResult> callback) {
        releaseInternal(false, callback);
        return true;
    }

    private OperationResult releaseInternal(boolean isSynced, final ResultCallback<OperationResult> callback) {
        verifyAccess();
        switch (this.m_State) {
            case RELEASING:
            case RELEASED:
                if (callback != null) {
                    callback.onResult(null, OperationResult.SUCCESS);
                }
                return OperationResult.SUCCESS;
            default:
                Log.v(TAG, "releaseInternal() - Sync : ", Boolean.valueOf(isSynced));
                changeState(State.RELEASING);
                this.m_StartToken = null;
                this.m_PendingStartInfo = null;
                this.m_PendingStopInfo = null;
                this.m_StartParams = null;
                this.m_SupportedPreviewSizes.clear();
                if (isSynced) {
                    if (onRelease(true, null, null)) {
                        onReleased(OperationResult.SUCCESS);
                        return OperationResult.SUCCESS;
                    }
                    onReleased(OperationResult.FAIL);
                    return OperationResult.FAIL;
                } else if (onRelease(false, this.m_Handler, new ResultCallback<OperationResult>() {
                    /* class com.oneplus.faceunlock.camera.CameraDevice.AnonymousClass3 */

                    public void onResult(Token token, OperationResult result) {
                        CameraDevice.this.onReleased(result);
                        if (callback != null) {
                            callback.onResult(null, result);
                        }
                    }
                })) {
                    return OperationResult.SUCCESS;
                } else {
                    return OperationResult.FAIL;
                }
        }
    }

    public OperationResult releaseSync() {
        return releaseInternal(true, null);
    }

    private void setPendingAction(PendingAction action) {
        if (this.m_PendingAction != action) {
            Log.v(TAG, "setPendingAction() - [" + Integer.toHexString(hashCode()) + "] Action : " + action);
            this.m_PendingAction = action;
        }
    }

    public boolean start(StartParams params, final ResultCallback<OperationResult> callback) {
        verifyAccess();
        switch (AnonymousClass7.$SwitchMap$com$oneplus$faceunlock$camera$CameraDevice$State[this.m_State.ordinal()]) {
            case 3:
            case 7:
                setPendingAction(PendingAction.START);
                if (this.m_PendingStartInfo == null) {
                    this.m_PendingStartInfo = new PendingStartInfo(params, callback);
                    return true;
                } else if (callback == null) {
                    return true;
                } else {
                    callback.onResult(null, OperationResult.SUCCESS);
                    return true;
                }
            case 4:
            case FaceEngine.EXTRACT_RESULT_FACE_KEEP /*{ENCODED_INT: 6}*/:
                changeState(State.STARTING);
                Token token = new Token();
                this.m_StartToken = token;
                Log.v(TAG, "start() - Token : " + this.m_StartToken);
                this.m_StartParams = params;
                return onStart(token, params, this.m_Handler, new ResultCallback<OperationResult>() {
                    /* class com.oneplus.faceunlock.camera.CameraDevice.AnonymousClass4 */

                    public void onResult(Token token, OperationResult result) {
                        CameraDevice.this.onStarted(token, result);
                        if (callback != null) {
                            callback.onResult(null, result);
                        }
                    }
                });
            case 5:
                initialize(this.m_OpenParams);
                setPendingAction(PendingAction.START);
                if (this.m_PendingStartInfo == null) {
                    this.m_PendingStartInfo = new PendingStartInfo(params, callback);
                    return true;
                } else if (callback == null) {
                    return true;
                } else {
                    callback.onResult(null, OperationResult.SUCCESS);
                    return true;
                }
            case 8:
            case 9:
                if (callback == null) {
                    return true;
                }
                callback.onResult(null, OperationResult.SUCCESS);
                return true;
            default:
                Log.w(TAG, "start() - Invalid state : " + this.m_State);
                if (callback != null) {
                    callback.onResult(null, OperationResult.FAIL);
                }
                return false;
        }
    }

    private void startPendingStartInfo(long delayMillis) {
        StartParams params = this.m_PendingStartInfo.params;
        ResultCallback<OperationResult> callback = this.m_PendingStartInfo.callback;
        this.m_PendingStartInfo = null;
        if (delayMillis <= 0) {
            start(params, callback);
            return;
        }
        if (this.m_StartRunnable != null) {
            this.m_Handler.removeCallbacks(this.m_StartRunnable);
        }
        this.m_StartRunnable = new StartRunnable(params, callback);
        this.m_Handler.postDelayed(this.m_StartRunnable, delayMillis);
    }

    public boolean stop(final ResultCallback<OperationResult> callback) {
        verifyAccess();
        switch (AnonymousClass7.$SwitchMap$com$oneplus$faceunlock$camera$CameraDevice$State[this.m_State.ordinal()]) {
            case 3:
            case 4:
            case FaceEngine.EXTRACT_RESULT_FACE_KEEP /*{ENCODED_INT: 6}*/:
            case 7:
                if (callback == null) {
                    return true;
                }
                callback.onResult(null, OperationResult.SUCCESS);
                return true;
            case 5:
            default:
                Log.w(TAG, "stop() - Invalid state : " + this.m_State);
                if (callback != null) {
                    callback.onResult(null, OperationResult.FAIL);
                }
                return false;
            case 8:
                Log.v(TAG, "stop() - Token : " + this.m_StartToken);
                changeState(State.STOPPING);
                Token token = this.m_StartToken;
                return onStop(this.m_StartToken, this.m_Handler, new ResultCallback<OperationResult>() {
                    /* class com.oneplus.faceunlock.camera.CameraDevice.AnonymousClass5 */

                    public void onResult(Token token, OperationResult result) {
                        CameraDevice.this.onStopped(token, result);
                        if (callback != null) {
                            callback.onResult(null, result);
                        }
                    }
                });
            case 9:
                setPendingAction(PendingAction.STOP);
                if (this.m_PendingStopInfo == null) {
                    this.m_PendingStopInfo = new PendingStopInfo(callback);
                    return true;
                } else if (callback == null) {
                    return true;
                } else {
                    callback.onResult(null, OperationResult.SUCCESS);
                    return true;
                }
        }
    }

    /* access modifiers changed from: package-private */
    public void updateFaceKeyPoints(final float[] faceKeyPoints) {
        if (!isRelease() && this.m_StartParams != null && this.m_StartParams.faceKeyPointsCallback != null) {
            this.m_Handler.post(new Runnable() {
                /* class com.oneplus.faceunlock.camera.CameraDevice.AnonymousClass6 */

                public void run() {
                    if (!CameraDevice.this.isRelease() && CameraDevice.this.m_StartParams != null && CameraDevice.this.m_StartParams.faceKeyPointsCallback != null) {
                        CameraDevice.this.m_StartParams.faceKeyPointsCallback.onFaceKeyPointsUpdated(faceKeyPoints);
                    }
                }
            });
        }
    }

    private static boolean useCamera2API() {
        return false;
        Log.d(TAG, "useCamera2API() - Board : " + Build.BOARD);
        String str = Build.BOARD;
        char c = 65535;
        switch (str.hashCode()) {
            case 1348394469:
                if (str.equals("msm8996")) {
                    c = 0;
                    break;
                }
                break;
            case 1348394471:
                if (str.equals("msm8998")) {
                    c = 1;
                    break;
                }
                break;
        }
        switch (c) {
            case 0:
            case 1:
                return false;
            default:
                return true;
        }
    }

    private void verifyAccess() {
        if (Thread.currentThread() != this.m_Handler.getLooper().getThread()) {
            throw new RuntimeException("Cross-thread access.");
        }
    }
}