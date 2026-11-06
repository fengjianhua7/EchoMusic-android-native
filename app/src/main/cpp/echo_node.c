#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jint JNICALL Java_cc_echomusic_android_NativeNode_start(JNIEnv *env,jclass type,jstring script) {
    (void)type;
    const char *path=(*env)->GetStringUTFChars(env,script,0);
    if(!path)return -1;
    const char *arguments[]={"node","--max-old-space-size=64","--max-semi-space-size=4",path};
    size_t length=0;for(int i=0;i<4;i++)length+=strlen(arguments[i])+1;
    char *storage=(char*)malloc(length);
    if(!storage){(*env)->ReleaseStringUTFChars(env,script,path);return -2;}
    char *argv[5]={0};size_t offset=0;for(int i=0;i<4;i++){argv[i]=storage+offset;size_t n=strlen(arguments[i])+1;memcpy(argv[i],arguments[i],n);offset+=n;}
    (*env)->ReleaseStringUTFChars(env,script,path);
    void *compat=dlopen("libecho_compat.so",RTLD_NOW|RTLD_GLOBAL);
    if(!compat){free(storage);return -4;}
    void *library=dlopen("libnode.so",RTLD_NOW|RTLD_GLOBAL);
    int (*start)(int,char**) = library?(int (*)(int,char**))dlsym(library,"_ZN4node5StartEiPPc"):0;
    if(!start){free(storage);return -3;}
    int result=start(4,argv);free(storage);return result;
}
