/* API23 backports for the actual strong imports of the ARMv7 Node runtime.
 * Uses real rtnetlink interface/address enumeration, not fabricated loopback data.
 */
#include "echo_barrier.h"
#include <dlfcn.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#define in6addr_any echo_header_in6addr_any
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/if_packet.h>
#undef in6addr_any

const struct in6_addr in6addr_any=IN6ADDR_ANY_INIT;
static int (*real_get)(struct ifaddrs**);static void (*real_free)(struct ifaddrs*);
static int (*real_init)(pthread_barrier_t*,const pthread_barrierattr_t*,unsigned);
static int (*real_wait)(pthread_barrier_t*);static int (*real_destroy)(pthread_barrier_t*);
static pthread_once_t initialized=PTHREAD_ONCE_INIT;
static void initialize(void){void *lib=dlopen("libc.so",RTLD_NOW|RTLD_LOCAL);if(!lib)return;
    real_get=(int(*)(struct ifaddrs**))dlsym(lib,"getifaddrs");real_free=(void(*)(struct ifaddrs*))dlsym(lib,"freeifaddrs");
    real_init=(int(*)(pthread_barrier_t*,const pthread_barrierattr_t*,unsigned))dlsym(lib,"pthread_barrier_init");real_wait=(int(*)(pthread_barrier_t*))dlsym(lib,"pthread_barrier_wait");real_destroy=(int(*)(pthread_barrier_t*))dlsym(lib,"pthread_barrier_destroy");
}
int pthread_barrier_init(pthread_barrier_t *barrier,const pthread_barrierattr_t *attr,unsigned count){pthread_once(&initialized,initialize);if(real_init&&real_wait&&real_destroy)return real_init(barrier,attr,count);if(attr&&*attr!=PTHREAD_PROCESS_PRIVATE)return ENOTSUP;struct echo_barrier *b=0;int result=echo_barrier_create(&b,count);if(!result){memset(barrier,0,sizeof(*barrier));memcpy(barrier,&b,sizeof(b));}return result;}
int pthread_barrier_wait(pthread_barrier_t *barrier){pthread_once(&initialized,initialize);if(real_init&&real_wait&&real_destroy)return real_wait(barrier);struct echo_barrier *b=0;memcpy(&b,barrier,sizeof(b));return echo_barrier_enter(b);}
int pthread_barrier_destroy(pthread_barrier_t *barrier){pthread_once(&initialized,initialize);if(real_init&&real_wait&&real_destroy)return real_destroy(barrier);struct echo_barrier *b=0;memcpy(&b,barrier,sizeof(b));int result=echo_barrier_dispose(b);if(!result)memset(barrier,0,sizeof(*barrier));return result;}

struct item {struct ifaddrs value;struct sockaddr_storage address,mask,other;char name[IF_NAMESIZE];};
struct link {struct link *next;int index;unsigned flags;char name[IF_NAMESIZE];};
struct enumeration {struct ifaddrs *head,**tail;struct link *links;};
static void free_items(struct ifaddrs *item){while(item){struct ifaddrs *next=item->ifa_next;free(item);item=next;}}
static struct item *new_item(struct enumeration *state,const char *name,unsigned flags){struct item *item=calloc(1,sizeof(*item));if(!item)return 0;strncpy(item->name,name,IF_NAMESIZE-1);item->value.ifa_name=item->name;item->value.ifa_flags=flags;item->value.ifa_addr=(struct sockaddr*)&item->address;*state->tail=&item->value;state->tail=&item->value.ifa_next;return item;}
static void inet_address(struct sockaddr_storage *storage,int family,const void *data,unsigned index){if(family==AF_INET){struct sockaddr_in *out=(struct sockaddr_in*)storage;out->sin_family=AF_INET;memcpy(&out->sin_addr,data,4);}else{struct sockaddr_in6 *out=(struct sockaddr_in6*)storage;out->sin6_family=AF_INET6;memcpy(&out->sin6_addr,data,16);if(IN6_IS_ADDR_LINKLOCAL(&out->sin6_addr)||IN6_IS_ADDR_MC_LINKLOCAL(&out->sin6_addr))out->sin6_scope_id=index;}}
static int parse_link(struct enumeration *state,struct nlmsghdr *header){if(header->nlmsg_len<NLMSG_LENGTH(sizeof(struct ifinfomsg))){errno=EPROTO;return -1;}struct ifinfomsg *info=NLMSG_DATA(header);int length=IFLA_PAYLOAD(header);const char *name=0;const void *mac=0;int mac_length=0;
    for(struct rtattr *attr=IFLA_RTA(info);RTA_OK(attr,length);attr=RTA_NEXT(attr,length)){if(attr->rta_type==IFLA_IFNAME&&RTA_PAYLOAD(attr)>0&&memchr(RTA_DATA(attr),0,RTA_PAYLOAD(attr)))name=RTA_DATA(attr);if(attr->rta_type==IFLA_ADDRESS){mac=RTA_DATA(attr);mac_length=RTA_PAYLOAD(attr);}}
    if(!name)return 0;struct link *link=calloc(1,sizeof(*link));if(!link)return -1;link->index=info->ifi_index;link->flags=info->ifi_flags;strncpy(link->name,name,IF_NAMESIZE-1);link->next=state->links;state->links=link;
    struct item *item=new_item(state,name,info->ifi_flags);if(!item)return -1;struct sockaddr_ll *addr=(struct sockaddr_ll*)&item->address;addr->sll_family=AF_PACKET;addr->sll_ifindex=info->ifi_index;addr->sll_hatype=info->ifi_type;addr->sll_halen=(unsigned char)(mac_length>8?8:mac_length);if(mac&&addr->sll_halen)memcpy(addr->sll_addr,mac,addr->sll_halen);return 0;
}
static int parse_address(struct enumeration *state,struct nlmsghdr *header){if(header->nlmsg_len<NLMSG_LENGTH(sizeof(struct ifaddrmsg))){errno=EPROTO;return -1;}struct ifaddrmsg *info=NLMSG_DATA(header);int family=info->ifa_family,size=family==AF_INET?4:family==AF_INET6?16:0;if(!size)return 0;struct link *link=state->links;while(link&&link->index!=(int)info->ifa_index)link=link->next;if(!link)return 0;
    const void *address=0,*local=0,*broadcast=0;int length=IFA_PAYLOAD(header);for(struct rtattr *attr=IFA_RTA(info);RTA_OK(attr,length);attr=RTA_NEXT(attr,length)){if(RTA_PAYLOAD(attr)<size)continue;if(attr->rta_type==IFA_ADDRESS)address=RTA_DATA(attr);if(attr->rta_type==IFA_LOCAL)local=RTA_DATA(attr);if(attr->rta_type==IFA_BROADCAST)broadcast=RTA_DATA(attr);}
    if(!local)local=address;if(!local)return 0;struct item *item=new_item(state,link->name,link->flags);if(!item)return -1;inet_address(&item->address,family,local,info->ifa_index);
    unsigned prefix=info->ifa_prefixlen;if(prefix>(unsigned)size*8){errno=EPROTO;return -1;}unsigned char mask[16]={0};for(unsigned i=0;i<prefix;i++)mask[i/8]|=(unsigned char)(0x80>>(i%8));inet_address(&item->mask,family,mask,info->ifa_index);item->value.ifa_netmask=(struct sockaddr*)&item->mask;
    const void *other=(link->flags&IFF_POINTOPOINT)?address:broadcast;if(other){inet_address(&item->other,family,other,info->ifa_index);item->value.ifa_broadaddr=(struct sockaddr*)&item->other;}return 0;
}
static int dump(int fd,int type,unsigned sequence,struct enumeration *state){struct {struct nlmsghdr header;union {struct ifinfomsg link;struct ifaddrmsg address;} body;} request;memset(&request,0,sizeof(request));request.header.nlmsg_len=NLMSG_LENGTH(type==RTM_GETLINK?sizeof(request.body.link):sizeof(request.body.address));request.header.nlmsg_type=type;request.header.nlmsg_flags=NLM_F_REQUEST|NLM_F_DUMP;request.header.nlmsg_seq=sequence;struct sockaddr_nl kernel;memset(&kernel,0,sizeof(kernel));kernel.nl_family=AF_NETLINK;
    if(sendto(fd,&request,request.header.nlmsg_len,0,(struct sockaddr*)&kernel,sizeof(kernel))<0)return -1;
    for(;;){unsigned char buffer[32768];struct iovec iov={buffer,sizeof(buffer)};struct msghdr message;memset(&message,0,sizeof(message));message.msg_iov=&iov;message.msg_iovlen=1;ssize_t count;do{count=recvmsg(fd,&message,0);}while(count<0&&errno==EINTR);if(count<=0)return -1;if(message.msg_flags&MSG_TRUNC){errno=EOVERFLOW;return -1;}int length=(int)count;for(struct nlmsghdr *header=(struct nlmsghdr*)buffer;NLMSG_OK(header,length);header=NLMSG_NEXT(header,length)){if(header->nlmsg_seq!=sequence)continue;if(header->nlmsg_type==NLMSG_DONE){if(header->nlmsg_flags&NLM_F_DUMP_INTR){errno=EAGAIN;return -1;}return 0;}if(header->nlmsg_type==NLMSG_ERROR){errno=EIO;if(header->nlmsg_len>=NLMSG_LENGTH(sizeof(struct nlmsgerr))){int error=((struct nlmsgerr*)NLMSG_DATA(header))->error;if(!error)continue;errno=-error;}return -1;}if(header->nlmsg_type==RTM_NEWLINK&&parse_link(state,header))return -1;if(header->nlmsg_type==RTM_NEWADDR&&parse_address(state,header))return -1;}}
}
int getifaddrs(struct ifaddrs **result){if(!result){errno=EINVAL;return -1;}pthread_once(&initialized,initialize);if(real_get&&real_free)return real_get(result);*result=0;struct enumeration state;memset(&state,0,sizeof(state));state.tail=&state.head;
    int fd=socket(AF_NETLINK,SOCK_RAW|SOCK_CLOEXEC,NETLINK_ROUTE);if(fd<0)return -1;struct timeval timeout={5,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout));struct sockaddr_nl local;memset(&local,0,sizeof(local));local.nl_family=AF_NETLINK;
    int status=bind(fd,(struct sockaddr*)&local,sizeof(local));if(!status)status=dump(fd,RTM_GETLINK,1,&state);if(!status)status=dump(fd,RTM_GETADDR,2,&state);int saved_errno=errno;close(fd);while(state.links){struct link *next=state.links->next;free(state.links);state.links=next;}if(status){free_items(state.head);errno=saved_errno;return -1;}*result=state.head;return 0;
}
void freeifaddrs(struct ifaddrs *items){pthread_once(&initialized,initialize);if(real_get&&real_free)real_free(items);else free_items(items);}
