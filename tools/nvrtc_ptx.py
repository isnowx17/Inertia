# Compiles a CUDA C++ file to PTX with NVRTC.  usage: nvrtc_ptx.py <libnvrtc.so> <in.cu> <out.ptx> <arch>
import ctypes, sys
lib = ctypes.CDLL(sys.argv[1])
src = open(sys.argv[2], 'rb').read()
prog = ctypes.c_void_p()
assert lib.nvrtcCreateProgram(ctypes.byref(prog), src, sys.argv[2].encode(), 0, None, None) == 0
opts = [b'--gpu-architecture=' + sys.argv[4].encode(), b'--use_fast_math', b'-default-device', b'-w']
arr = (ctypes.c_char_p * len(opts))(*opts)
res = lib.nvrtcCompileProgram(prog, len(opts), arr)
size = ctypes.c_size_t()
lib.nvrtcGetProgramLogSize(prog, ctypes.byref(size))
log = ctypes.create_string_buffer(size.value)
lib.nvrtcGetProgramLog(prog, log)
if log.value.strip():
    print(log.value.decode(), file=sys.stderr)
if res != 0:
    sys.exit('nvrtc failed: %d' % res)
lib.nvrtcGetPTXSize(prog, ctypes.byref(size))
ptx = ctypes.create_string_buffer(size.value)
lib.nvrtcGetPTX(prog, ptx)
open(sys.argv[3], 'wb').write(ptx.value)
print('ok', len(ptx.value), 'bytes')
