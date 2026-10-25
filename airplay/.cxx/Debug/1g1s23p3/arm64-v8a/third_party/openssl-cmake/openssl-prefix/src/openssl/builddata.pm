package OpenSSL::safe::installdata;

use strict;
use warnings;
use Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(
    @PREFIX
    @libdir
    @BINDIR @BINDIR_REL_PREFIX
    @LIBDIR @LIBDIR_REL_PREFIX
    @INCLUDEDIR @INCLUDEDIR_REL_PREFIX
    @APPLINKDIR @APPLINKDIR_REL_PREFIX
    @ENGINESDIR @ENGINESDIR_REL_LIBDIR
    @MODULESDIR @MODULESDIR_REL_LIBDIR
    @PKGCONFIGDIR @PKGCONFIGDIR_REL_LIBDIR
    @CMAKECONFIGDIR @CMAKECONFIGDIR_REL_LIBDIR
    $VERSION @LDLIBS
);

our @PREFIX                     = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl' );
our @libdir                     = ( '' );
our @BINDIR                     = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl/apps' );
our @BINDIR_REL_PREFIX          = ( 'apps' );
our @LIBDIR                     = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl' );
our @LIBDIR_REL_PREFIX          = ( '' );
our @INCLUDEDIR                 = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl/include', '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl/include' );
our @INCLUDEDIR_REL_PREFIX      = ( 'include', './include' );
our @APPLINKDIR                 = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl/ms' );
our @APPLINKDIR_REL_PREFIX      = ( 'ms' );
our @ENGINESDIR                 = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl/engines' );
our @ENGINESDIR_REL_LIBDIR      = ( 'engines' );
our @MODULESDIR                 = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl/providers' );
our @MODULESDIR_REL_LIBDIR      = ( 'providers' );
our @PKGCONFIGDIR               = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl' );
our @PKGCONFIGDIR_REL_LIBDIR    = ( '.' );
our @CMAKECONFIGDIR             = ( '/data/home/zyq/IdeaProjects/github/FongMi/TV/airplay/.cxx/Debug/1g1s23p3/arm64-v8a/third_party/openssl-cmake/openssl-prefix/src/openssl' );
our @CMAKECONFIGDIR_REL_LIBDIR  = ( '.' );
our $VERSION                    = '3.4.4';
our @LDLIBS                     =
    # Unix and Windows use space separation, VMS uses comma separation
    $^O eq 'VMS'
    ? split(/ *, */, '-ldl -pthread ')
    : split(/ +/, '-ldl -pthread ');

1;
