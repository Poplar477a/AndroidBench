// POC A：试图建立网络连接（应被 CLONE_NEWNET 或 seccomp 拦截）
#include <stdio.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
int main(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    printf("POC-A socket() = %d\n", fd);
    if (fd >= 0) {
        struct sockaddr_in a;
        a.sin_family = AF_INET; a.sin_port = htons(80);
        a.sin_addr.s_addr = inet_addr("223.5.5.5");
        int rc = connect(fd, (struct sockaddr*)&a, sizeof(a));
        printf("POC-A connect() = %d\n", rc);
    }
    return 0;
}
