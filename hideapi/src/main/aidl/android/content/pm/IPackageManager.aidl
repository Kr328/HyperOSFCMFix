package android.content.pm;

interface IPackageManager {
    void setPackageStoppedState(in String packageName, boolean stopped, int userId);
}
