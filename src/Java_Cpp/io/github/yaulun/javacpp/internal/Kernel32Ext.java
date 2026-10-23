package io.github.yaulun.javacpp.internal;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/** Windows loader operations; BOOL uses four bytes and WINAPI uses stdcall. */
public interface Kernel32Ext extends StdCallLibrary {
  boolean SetDllDirectoryW(WString directory);

  Pointer LoadLibraryExW(WString file, Pointer reserved, int flags);

  boolean FreeLibrary(Pointer module);
}
