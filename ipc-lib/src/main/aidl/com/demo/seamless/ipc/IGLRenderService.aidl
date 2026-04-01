package com.demo.seamless.ipc;

import android.view.Surface;

interface IGLRenderService {
    void registerClient(String clientId);
    void unregisterClient(String clientId);
    void setSurface(String clientId, in Surface surface, int width, int height);
    void removeSurface(String clientId);
    void updateCamera(String clientId, float rotX, float rotY, float dist);
    void requestTransition(String clientId, String targetPkg, String targetAct,
                           long durationMs, float rotX, float rotY, float dist);
}
