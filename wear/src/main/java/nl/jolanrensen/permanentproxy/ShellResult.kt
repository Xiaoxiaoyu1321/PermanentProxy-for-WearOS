package nl.jolanrensen.permanentproxy

import android.os.Parcel
import android.os.Parcelable

/**
 * Result of a privileged shell command executed through Shizuku.
 *
 * Kept deliberately tiny and [Parcelable] so it can cross the binder boundary between the app
 * process and the Shizuku user-service process. stderr is merged into [output] on purpose:
 * reading two streams of a child process from two threads is a classic source of deadlocks,
 * and for `settings`/`pm` commands the interleaved output is easier to show to the user anyway.
 */
data class ShellResult(
    val exitCode: Int,
    val output: String
) : Parcelable {

    val isSuccess: Boolean
        get() = exitCode == 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(exitCode)
        dest.writeString(output)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ShellResult> {
        override fun createFromParcel(parcel: Parcel): ShellResult =
            ShellResult(parcel.readInt(), parcel.readString().orEmpty())

        override fun newArray(size: Int): Array<ShellResult?> = arrayOfNulls(size)
    }
}
