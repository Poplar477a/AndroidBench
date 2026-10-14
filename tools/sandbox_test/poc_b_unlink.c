// POC B：试图删除系统文件（应因降权得到 EACCES）
#include <stdio.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/types.h>
int main(void) {
    printf("POC-B uid=%d gid=%d\n", (int)getuid(), (int)getgid());
    int rc = unlink("/system/bin/sh");
    printf("POC-B unlink(/system/bin/sh) = %d errno=%d (%s)\n", rc, errno, strerror(errno));
    rc = unlink("/data/local/tmp/should_not_delete");
    printf("POC-B unlink(/data/local/tmp/...) = %d errno=%d (%s)\n", rc, errno, strerror(errno));
    return 0;
}
