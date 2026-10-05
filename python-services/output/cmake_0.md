## CMake

### 常用指令

cmake_minimum_required (VERSION 3.16)//cmake最低版本

project (demo)//项目名称

include_directories (test_func test_func1)//添加头文件（.h）的搜索路径，告诉编译器去 test_func test_func1下搜索头文件

aux_source_directory(dir var)//将dir目录下的所有源文件存储到变量var中

set( SRC_LIST
 ./main.c
 ./testFunc1.c
 ./testFunc.c)//使用set命令将指定的源文件存储到变量SRC_LIST中

file(GLOB_RECURSE SRC ${PROJECT_SOURCE_DIR}/src/*.cpp)//获取指定目录下所有匹配指定模式的文件路径

add_subdirectory(source_dir [binary_dir] [EXCLUDE_FROM_ALL])//增加编译子目录，子文件夹下含有CMakeLists.txt

add_executable(main ${var})//将程序的各个模块（源代码文件、库文件等）整合在一起，并链接到可执行文件main

//注意调用变量的写法

target_include_directories(<target> [SYSTEM] [AFTER|BEFORE] <INTERFACE|PUBLIC|PRIVATE> [items1...] <INTERFACE|PUBLIC|PRIVATE> [items2...]...))//该命令可以指定目标（exe或者so文件）需要包含的头文件路径，使得项目可以访问该目录下的头文件（系统存在的）

add_library (testFunc_shared SHARED ${SRC_LIST})//生成共享库（第二个参数默认静态库）

find_library(<VAR> name1 [path1 path2...])//找库 <VAR>是一个变量，用于存储找到的库文件的路径

set_target_properties (testFunc_shared PROPERTIES OUTPUT_NAME "testFunc")//设置最终生成库文件的名称及功能

set (LIBRARY_OUTPUT_PATH ${PROJECT_SOURCE_DIR}/lib)//设置库文件输出路径，此设置对于将生成的库文件与其他构建产物（如可执行文件、静态库）分开放置非常有用，以保持项目结构的整洁和可维护性。

target_link_libraries(main ${TESTFUNC_LIB}) // 把目标文件与库文件进行链接

#### 预定义变量

1. CMAKE_SOURCE_DIR：表示 CMakeLists.txt 文件的根源录的路径。

2. CMAKE_BINARY_DIR：表示输出目录的路径，即编译生成的可执行文件、库文件等所在的路径。