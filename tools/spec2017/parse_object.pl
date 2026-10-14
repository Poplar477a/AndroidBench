#!/usr/bin/perl
# 从 SPEC 基准的 Spec/object.pm 提取构建元数据（正则解析，绝不执行被解析文件）
# 支持：$bench_cflags / $bench_flags（含 .= 追加与 qw() 列表、$Config{'byteorder'} 解析）
use strict; use warnings;
use Config;
my $f = shift or die "usage: parse_object.pl <object.pm>\n";
local $/; open my $fh, '<', $f or die "$f: $!\n"; my $s = <$fh>; close $fh;
sub grab { my ($re) = @_; return ( $s =~ $re ) ? $1 : ''; }

my $lang = grab(qr/benchlang\s*=\s*'([^']*)'/);
my $exe  = grab(qr/exename\s*=\s*'([^']*)'/);
my $fc   = grab(qr/floatcompare\s*=\s*'([^']*)'/);
my $ct   = grab(qr/calctol\s*=\s*'?([0-9.]+)'?/);
my $math = grab(qr/need_math\s*=\s*'([^']*)'/);

# 收集 $bench_cflags / $bench_flags（含 .= 形式）
my $flags = '';
my $byteorder = $Config{'byteorder'};
$byteorder = '12345678' unless defined $byteorder and length $byteorder;
foreach my $stmt ($s =~ /\$bench_(?:c?flags)\s*(?:\.=|=)\s*(.+?);/gs) {
  my $part = $stmt;
  $part =~ s/\$Config\s*\{\s*['"]byteorder['"]\s*\}/$byteorder/g;
  # 先摘出 qw(...) 列表，再把剩余文本去引号/包裹符号后整体并入（保持 0x12345678 这类拼接不被空格拆开）
  while ($part =~ /qw\((.*?)\)/gs) { $flags .= ' ' . $1; }
  $part =~ s/qw\(.*?\)//gs;
  $part =~ s/['"]//g;
  $part =~ s/\bjoin\b//g;
  $part =~ s/[(),]//g;
  $flags .= ' ' . $part;
}
$flags =~ s/\s+/ /g; $flags =~ s/^\s+|\s+$//g;

my @src;
if    ($s =~ /\@sources\s*=\s*qw\((.*?)\)/s)      { @src = split ' ', $1; }
elsif ($s =~ /\@sources\s*=\s*\(qw\((.*?)\)\)/s)  { @src = split ' ', $1; }
@src = grep { !/^#/ && /\S/ } @src;

print "LANG=$lang\n";
print "EXE=$exe\n";
print "BENCH_CFLAGS=$flags\n";
print "NEED_MATH=$math\n";
print "FLOATCOMPARE=$fc\n";
print "CALCTOL=$ct\n";
print "SOURCES=", join(' ', @src), "\n";
