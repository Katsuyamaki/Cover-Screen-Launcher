package com.example.quadrantlauncher;

interface IShellService {
    void forceStop(String packageName);
    void runCommand(String command);
    // displayIndex: 0 = Main, 1 = Cover (usually)
    void setScreenOff(int displayIndex, boolean turnOff); 
}
