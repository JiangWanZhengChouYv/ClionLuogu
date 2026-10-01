// ClionLuogu 本地对拍用的 bits/stdc++.h 兼容头。
//
// 洛谷评测机是 g++，原生带 <bits/stdc++.h>；macOS 上的 Apple clang 用 libc++，没有这个头，
// 于是插件默认模板（以及所有已拉题生成的 .cpp）第一行就 fatal error。
// 插件只在**探测到目标编译器原生没有 bits/stdc++.h** 时，才把这个文件释放到临时目录并用
// -isystem 注入；g++/MinGW 走原生头，本文件不参与，也不会遮蔽真头。
//
// 列表逐项用 `clang++ -std=c++17 -O2 -fsyntax-only -include <头>` 实测筛过，
// libc++ 里没有的一律不放——climit、scoped_lock（由 <mutex> 提供）、malloc.h，
// 以及 libstdc++ 才有 / 根本不存在的 cstrings.h、multiset、multimap、promise（后两个分别来自 <set>、<future>）。
// 本地对拍只是提前发现问题，最终以提交给洛谷的评测结果为准。

#ifndef CLIONLUOGU_BITS_STDCPP_H
#define CLIONLUOGU_BITS_STDCPP_H

// 每一项都有 __has_include 保护：不同 libc++/libstdc++ 版本缺哪个就跳过哪个，
// 绝不因为兼容头本身缺项而把一份本来能编过的代码弄挂。

#if __has_include(<version>)
#include <version>
#endif
#if __has_include(<cstddef>)
#include <cstddef>
#endif
#if __has_include(<cstdlib>)
#include <cstdlib>
#endif
#if __has_include(<new>)
#include <new>
#endif
#if __has_include(<typeinfo>)
#include <typeinfo>
#endif
#if __has_include(<type_traits>)
#include <type_traits>
#endif
#if __has_include(<concepts>)
#include <concepts>
#endif
#if __has_include(<compare>)
#include <compare>
#endif
#if __has_include(<coroutine>)
#include <coroutine>
#endif
#if __has_include(<source_location>)
#include <source_location>
#endif
#if __has_include(<exception>)
#include <exception>
#endif
#if __has_include(<stdexcept>)
#include <stdexcept>
#endif
#if __has_include(<limits>)
#include <limits>
#endif
#if __has_include(<climits>)
#include <climits>
#endif
#if __has_include(<cfloat>)
#include <cfloat>
#endif
#if __has_include(<cstdint>)
#include <cstdint>
#endif
#if __has_include(<cinttypes>)
#include <cinttypes>
#endif
#if __has_include(<cmath>)
#include <cmath>
#endif
#if __has_include(<complex>)
#include <complex>
#endif
#if __has_include(<cfenv>)
#include <cfenv>
#endif
#if __has_include(<cctype>)
#include <cctype>
#endif
#if __has_include(<cwctype>)
#include <cwctype>
#endif
#if __has_include(<cstring>)
#include <cstring>
#endif
#if __has_include(<clocale>)
#include <clocale>
#endif
#if __has_include(<csetjmp>)
#include <csetjmp>
#endif
#if __has_include(<csignal>)
#include <csignal>
#endif
#if __has_include(<cstdbool>)
#include <cstdbool>
#endif
#if __has_include(<cstdalign>)
#include <cstdalign>
#endif
#if __has_include(<cuchar>)
#include <cuchar>
#endif
#if __has_include(<cwchar>)
#include <cwchar>
#endif
#if __has_include(<ciso646>)
#include <ciso646>
#endif
#if __has_include(<cassert>)
#include <cassert>
#endif
#if __has_include(<cerrno>)
#include <cerrno>
#endif
#if __has_include(<utility>)
#include <utility>
#endif
#if __has_include(<tuple>)
#include <tuple>
#endif
#if __has_include(<optional>)
#include <optional>
#endif
#if __has_include(<variant>)
#include <variant>
#endif
#if __has_include(<any>)
#include <any>
#endif
#if __has_include(<bit>)
#include <bit>
#endif
#if __has_include(<numeric>)
#include <numeric>
#endif
#if __has_include(<algorithm>)
#include <algorithm>
#endif
#if __has_include(<functional>)
#include <functional>
#endif
#if __has_include(<memory>)
#include <memory>
#endif
#if __has_include(<memory_resource>)
#include <memory_resource>
#endif
#if __has_include(<scoped_allocator>)
#include <scoped_allocator>
#endif
#if __has_include(<iterator>)
#include <iterator>
#endif
#if __has_include(<initializer_list>)
#include <initializer_list>
#endif
#if __has_include(<ranges>)
#include <ranges>
#endif
#if __has_include(<iosfwd>)
#include <iosfwd>
#endif
#if __has_include(<ios>)
#include <ios>
#endif
#if __has_include(<istream>)
#include <istream>
#endif
#if __has_include(<ostream>)
#include <ostream>
#endif
#if __has_include(<iostream>)
#include <iostream>
#endif
#if __has_include(<streambuf>)
#include <streambuf>
#endif
#if __has_include(<iomanip>)
#include <iomanip>
#endif
#if __has_include(<sstream>)
#include <sstream>
#endif
#if __has_include(<fstream>)
#include <fstream>
#endif
#if __has_include(<filesystem>)
#include <filesystem>
#endif
#if __has_include(<print>)
#include <print>
#endif
#if __has_include(<format>)
#include <format>
#endif
#if __has_include(<charconv>)
#include <charconv>
#endif
#if __has_include(<locale>)
#include <locale>
#endif
#if __has_include(<regex>)
#include <regex>
#endif
#if __has_include(<bitset>)
#include <bitset>
#endif
#if __has_include(<valarray>)
#include <valarray>
#endif
#if __has_include(<string>)
#include <string>
#endif
#if __has_include(<string_view>)
#include <string_view>
#endif
#if __has_include(<vector>)
#include <vector>
#endif
#if __has_include(<deque>)
#include <deque>
#endif
#if __has_include(<list>)
#include <list>
#endif
#if __has_include(<forward_list>)
#include <forward_list>
#endif
#if __has_include(<span>)
#include <span>
#endif
#if __has_include(<array>)
#include <array>
#endif
#if __has_include(<set>)
#include <set>
#endif
#if __has_include(<map>)
#include <map>
#endif
#if __has_include(<unordered_set>)
#include <unordered_set>
#endif
#if __has_include(<unordered_map>)
#include <unordered_map>
#endif
#if __has_include(<queue>)
#include <queue>
#endif
#if __has_include(<stack>)
#include <stack>
#endif
#if __has_include(<chrono>)
#include <chrono>
#endif
#if __has_include(<ratio>)
#include <ratio>
#endif
#if __has_include(<ctime>)
#include <ctime>
#endif
#if __has_include(<random>)
#include <random>
#endif
#if __has_include(<atomic>)
#include <atomic>
#endif
#if __has_include(<thread>)
#include <thread>
#endif
#if __has_include(<mutex>)
#include <mutex>
#endif
#if __has_include(<shared_mutex>)
#include <shared_mutex>
#endif
#if __has_include(<condition_variable>)
#include <condition_variable>
#endif
#if __has_include(<future>)
#include <future>
#endif
#if __has_include(<latch>)
#include <latch>
#endif
#if __has_include(<semaphore>)
#include <semaphore>
#endif
#if __has_include(<barrier>)
#include <barrier>
#endif
#if __has_include(<syncstream>)
#include <syncstream>
#endif
#if __has_include(<system_error>)
#include <system_error>
#endif
#if __has_include(<numbers>)
#include <numbers>
#endif

#endif  // CLIONLUOGU_BITS_STDCPP_H
