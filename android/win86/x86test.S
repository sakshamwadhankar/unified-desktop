# Phase 2 T2 guest test program: pure Linux x86-64 syscalls, no libc.
# Assembled with the NDK x86_64 clang as a static ELF. It is DATA on the phone:
# box64 loads it as a guest program; nothing on the Android exec path touches it.
# Prints "hello from phase2 x86-64 under box64", then ticks 3 times, 2 s apart,
# so the harness can readlink /proc/<pid>/exe while it is alive.

    .text
    .globl _start
_start:
    call hello
    mov  $3, %r9                    # remaining ticks
tick:
    cmp  $0, %r9
    je   done
    # clock_nanosleep(CLOCK_MONOTONIC=1, 0, &ts, NULL)
    mov  $230, %rax
    mov  $1, %rdi
    xor  %rsi, %rsi
    lea  ts(%rip), %rdx
    xor  %r10, %r10
    syscall
    call hello
    dec  %r9
    jmp  tick
done:
    mov  $60, %rax                  # exit(0)
    xor  %rdi, %rdi
    syscall

hello:
    mov  $1, %rax                   # write(1, msg, len)
    mov  $1, %rdi
    lea  msg(%rip), %rsi
    mov  $msglen, %rdx
    syscall
    ret

    .data
ts:
    .quad 2                         # tv_sec
    .quad 0                         # tv_nsec

    .section .rodata
msg:
    .ascii "hello from phase2 x86-64 under box64\n"
msglen = . - msg
