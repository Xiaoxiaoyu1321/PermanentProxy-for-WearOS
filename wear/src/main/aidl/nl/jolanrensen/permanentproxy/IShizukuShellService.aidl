// AIDL contract for the Shizuku "user service".
//
// The implementation of this interface (ShizukuShellService) is instantiated by the Shizuku
// server inside a dedicated process that runs with shell (uid 2000) or root (uid 0) identity.
// Everything the app cannot do on its own (writing secure settings) is delegated to it.
package nl.jolanrensen.permanentproxy;

import nl.jolanrensen.permanentproxy.ShellResult;

interface IShizukuShellService {

    /**
     * Reserved method, Shizuku calls it when the user service must be destroyed.
     * The transaction code 16777114 is defined by the Shizuku server.
     */
    void destroy() = 16777114;

    /**
     * Runs `command` through /system/bin/sh with the privileged identity and returns the
     * exit code plus the combined stdout/stderr output.
     */
    ShellResult exec(String command) = 1;

    /** Real Linux uid of the process this service runs in (0 = root, 2000 = shell). */
    int getShellUid() = 2;
}
