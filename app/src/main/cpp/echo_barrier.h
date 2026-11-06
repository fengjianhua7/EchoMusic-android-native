#ifndef ECHO_BARRIER_H
#define ECHO_BARRIER_H
#include <pthread.h>
#include <stdlib.h>
#include <errno.h>
/* Private-process reusable barrier. The same core is stress-tested on the host. */
struct echo_barrier {pthread_mutex_t mutex;pthread_cond_t changed;unsigned count,arrived,users,generation;};
static int echo_barrier_create(struct echo_barrier **output,unsigned count){
    if(!count)return EINVAL;struct echo_barrier *b=calloc(1,sizeof(*b));if(!b)return ENOMEM;
    int error=pthread_mutex_init(&b->mutex,0);if(error){free(b);return error;}
    error=pthread_cond_init(&b->changed,0);if(error){pthread_mutex_destroy(&b->mutex);free(b);return error;}
    b->count=count;*output=b;return 0;
}
static int echo_barrier_enter(struct echo_barrier *b){
    if(!b)return EINVAL;pthread_mutex_lock(&b->mutex);unsigned generation=b->generation;b->users++;
    int result=0;if(++b->arrived==b->count){b->arrived=0;b->generation++;pthread_cond_broadcast(&b->changed);result=-1;}
    else while(generation==b->generation)pthread_cond_wait(&b->changed,&b->mutex);
    b->users--;pthread_mutex_unlock(&b->mutex);return result;
}
static int echo_barrier_dispose(struct echo_barrier *b){
    if(!b)return EINVAL;pthread_mutex_lock(&b->mutex);if(b->users){pthread_mutex_unlock(&b->mutex);return EBUSY;}pthread_mutex_unlock(&b->mutex);
    pthread_cond_destroy(&b->changed);pthread_mutex_destroy(&b->mutex);free(b);return 0;
}
#endif
