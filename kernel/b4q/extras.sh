# Extra built-ins for the Larry kernels: filesystems and one netfilter match, nothing that changes core structs.
EXTRAS="-e ISO9660_FS -e JOLIET -e ZISOFS -e UDF_FS \
 -e SQUASHFS -e SQUASHFS_FILE_DIRECT -e SQUASHFS_DECOMP_MULTI_PERCPU -e SQUASHFS_XATTR -e SQUASHFS_ZLIB -e SQUASHFS_LZ4 -e SQUASHFS_LZO -e SQUASHFS_XZ -e SQUASHFS_ZSTD \
 -e NETFILTER_XT_MATCH_ADDRTYPE"
