package com.demo.seamless.ipc;

interface IGLRenderService {
    void registerClient(String clientId);
    void unregisterClient(String clientId);
    void requestTransition(String clientId, String targetPkg, String targetAct, long durationMs,
                           float currentRotX, float currentRotY, float currentDist);
}
