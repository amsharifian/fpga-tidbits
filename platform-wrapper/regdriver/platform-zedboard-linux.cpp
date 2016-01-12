// platform init-deinit functions for Linux on the ZedBoard
// note that this assumes the peripheral lives at address 0x43c00000, and that 256MB of
// unmanaged memory is available at address 0x10000000

/*
something like this can be used for the ZedBoard to ensure the kernel leaves the upper half
of the DDR alone:

env set fdt_high 0x10000000
env set initrd_high 0x10000000
env set bootargs "console=ttyPS0,115200 root=/dev/mmcblk0p2 rw rootwait earlyprintk cma=16m mem=256m"
*/

#include "platform.h"
#include "linuxphysregdriver.hpp"
#include <iostream>
#include <string>
using namespace std;

extern "C" {
  #include <unistd.h>
  #include <sys/types.h>
  #include <sys/wait.h>
}

LinuxPhysRegDriver * platform = 0;

WrapperRegDriver * initPlatform() {
  if(!platform) {
    platform = new LinuxPhysRegDriver((void *) 0x43c00000, (void *) 0x10000000, 256 * 1024 * 1024);
  }
  return (WrapperRegDriver *) platform;
}

void deinitPlatform(WrapperRegDriver * driver) {
  // TODO doing a delete here causes the zedboard to go in a loop, debug this
}

void loadBitfile(const char * accelName) {
  pid_t c_pid, pid;
  int status;

  c_pid = fork();
  // call a shell script to do the bitfile loading, fork & exec & wait
  const char * loader = "/root/bitfiles/load-bitfile.sh";

  if (c_pid == 0){
    execl(loader, loader, accelName, NULL);
    throw "execl failed";
  } else if (c_pid > 0){
    if( (pid = wait(&status)) < 0){
      throw "wait failed";
      _exit(1);
    }
  } else{
    throw ("fork failed");
    _exit(1);
  }
  cout << "loadBitfile finished: " << accelName << endl;
}
