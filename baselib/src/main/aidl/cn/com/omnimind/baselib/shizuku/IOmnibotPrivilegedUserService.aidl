package cn.com.omnimind.baselib.shizuku;

interface IOmnibotPrivilegedUserService {

    void destroy() = 16777114;

    String execute(String requestJson) = 1;

    oneway void cancel(String requestId) = 2;
}
