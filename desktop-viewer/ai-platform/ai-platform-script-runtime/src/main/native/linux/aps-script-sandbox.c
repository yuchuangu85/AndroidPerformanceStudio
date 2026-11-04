#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/sched.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#if defined(__x86_64__)
#define APS_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define APS_AUDIT_ARCH AUDIT_ARCH_AARCH64
#else
#error "Unqualified syscall ABI: only Linux x86_64 and aarch64 are supported"
#endif

#define DENY(nr) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (nr), 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM)

static int finite_limit(int resource) {
    struct rlimit limit;
    return getrlimit(resource, &limit) == 0 && limit.rlim_max != RLIM_INFINITY;
}

static int bound_processes(void) {
    struct rlimit limit;
    if (getrlimit(RLIMIT_NPROC, &limit) != 0) return 0;
    if (limit.rlim_max > 16) limit.rlim_max = 16;
    limit.rlim_cur = limit.rlim_max;
    /* Apply after bwrap created the user namespace, not against the GUI JVM's host UID threads. */
    return setrlimit(RLIMIT_NPROC, &limit) == 0;
}

static int isolate(void) {
    /* A single process keeps CPU/address-space limits aggregate across all interpreter threads. */
    const uint32_t thread_flags = CLONE_VM | CLONE_FS | CLONE_FILES | CLONE_SIGHAND |
        CLONE_THREAD | CLONE_SYSVSEM | CLONE_SETTLS | CLONE_PARENT_SETTID |
        CLONE_CHILD_CLEARTID | CLONE_CHILD_SETTID;
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, APS_AUDIT_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
        /* Reject x32 and alternate syscall tables, not just the native fork numbers. */
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, 0x40000000, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        DENY(__NR_fork),
        DENY(__NR_vfork),
#endif
        /* glibc can fall back from clone3 to the inspected legacy clone thread path. */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone3, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ENOSYS),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 9),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]) + 4),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 6),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 0, 4),
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, ~thread_flags),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, 0, 0, 2),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        DENY(__NR_socket),
        DENY(__NR_socketpair),
        DENY(__NR_ptrace),
        DENY(__NR_process_vm_readv),
        DENY(__NR_process_vm_writev),
        DENY(__NR_setns),
        DENY(__NR_unshare),
        DENY(__NR_mount),
        DENY(__NR_umount2),
        DENY(__NR_pivot_root),
        DENY(__NR_open_by_handle_at),
        DENY(__NR_bpf),
        DENY(__NR_userfaultfd),
        DENY(__NR_io_uring_setup),
        DENY(__NR_keyctl),
        DENY(__NR_add_key),
        DENY(__NR_request_key),
        DENY(__NR_shmget),
        DENY(__NR_shmat),
        DENY(__NR_shmdt),
        DENY(__NR_shmctl),
        DENY(__NR_semget),
        DENY(__NR_semctl),
        DENY(__NR_semop),
        DENY(__NR_semtimedop),
        DENY(__NR_msgget),
        DENY(__NR_msgsnd),
        DENY(__NR_msgrcv),
        DENY(__NR_msgctl),
        DENY(__NR_perf_event_open),
        DENY(__NR_pidfd_getfd),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {
        .len = (unsigned short)(sizeof(filter) / sizeof(filter[0])), .filter = filter
    };
    return prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0 &&
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program) == 0;
}

int main(int argc, char **argv) {
    if (argc < 2 || argv[1][0] != '/' || getuid() == 0 || geteuid() == 0 || !finite_limit(RLIMIT_AS) ||
        !finite_limit(RLIMIT_CPU) || !finite_limit(RLIMIT_FSIZE) ||
        !finite_limit(RLIMIT_NOFILE) || !bound_processes() || !isolate()) {
        fputs("E_LINUX_SANDBOX_HELPER\n", stderr);
        return 125;
    }
    execv(argv[1], argv + 1);
    fputs("E_SCRIPT_RUNTIME_UNAVAILABLE\n", stderr);
    return 126;
}
