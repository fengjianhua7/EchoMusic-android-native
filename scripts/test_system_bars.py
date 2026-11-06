"""Exercise actual SystemBars source against a lazy-decor PhoneWindow contract.
Optional argument: previous source zip, which must reproduce the startup failure.
This host JVM check is not an Android device test.
"""
from pathlib import Path
import tempfile, subprocess, os, sys, zipfile

root=Path(__file__).resolve().parents[1]
java=Path(os.environ['JAVA_HOME'])/'bin'
stubs={
 'android/os/Build.java':'package android.os; public class Build {public static class VERSION {public static int SDK_INT=30;}}',
 'android/view/WindowInsetsController.java':'package android.view; public class WindowInsetsController {public static final int APPEARANCE_LIGHT_STATUS_BARS=8,APPEARANCE_LIGHT_NAVIGATION_BARS=16;public int appearance;public void setSystemBarsAppearance(int value,int mask){appearance=value;}}',
 'android/view/View.java':'package android.view; public class View {public static final int SYSTEM_UI_FLAG_LIGHT_STATUS_BAR=8192,SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR=16;public int flags;public WindowInsetsController controller=new WindowInsetsController();public WindowInsetsController getWindowInsetsController(){return controller;}public void setSystemUiVisibility(int value){flags=value;}}',
 'android/view/Window.java':'package android.view; public class Window {public View decor;public int status,nav;public View getDecorView(){if(decor==null)decor=new View();return decor;}public WindowInsetsController getInsetsController(){return decor.getWindowInsetsController();}public void setStatusBarColor(int value){status=value;}public void setNavigationBarColor(int value){nav=value;}public void setStatusBarContrastEnforced(boolean value){}public void setNavigationBarContrastEnforced(boolean value){}}',
 'cc/echomusic/android/SystemBarsTest.java':'''package cc.echomusic.android;
import android.os.Build;import android.view.*;
public class SystemBarsTest {public static void main(String[] args)throws Exception{int checks=0;for(int api:new int[]{30,35,29,26,23}){Build.VERSION.SDK_INT=api;Window w=new Window();SystemBars.apply(w);if(w.decor==null||w.status!=0xffa5f2f3||w.nav!=0xffa5f2f3)throw new AssertionError("lazy window initialization");checks++;if(api>=30?w.decor.controller.appearance!=24:w.decor.flags!=(api>=26?8208:8192))throw new AssertionError("dark system icons");checks++;SystemBars.apply(w);checks++;}Window themed=new Window();SystemBars.class.getDeclaredMethod("applyPlayer",Window.class).invoke(null,themed);if(themed.status!=0xff213e52||themed.nav!=0xff213e52)throw new AssertionError("player bar color");checks++;SystemBars.apply(themed);if(themed.status!=0xffa5f2f3)throw new AssertionError("main bar restore");checks++;Window w=new Window();w.getDecorView().controller=null;Build.VERSION.SDK_INT=30;SystemBars.apply(w);checks++;SystemBars.apply(null);checks++;System.out.println("PASS: "+checks+" system-bar startup contract checks");}}
'''
}
def run(source):
 with tempfile.TemporaryDirectory(prefix='echo-bars-') as temp:
  folder=Path(temp)
  for name,contents in {**stubs,'cc/echomusic/android/SystemBars.java':source}.items():
   path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(contents,encoding='utf-8')
  subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(folder/'classes'),*[str(p) for p in folder.rglob('*.java')]],check=True,capture_output=True)
  return subprocess.run([str(java/'java.exe'),'-cp',str(folder/'classes'),'cc.echomusic.android.SystemBarsTest'],capture_output=True,text=True)
if len(sys.argv)>1:
 with zipfile.ZipFile(sys.argv[1]) as archive:old=archive.read('EchoMusic/android-native/app/src/main/java/cc/echomusic/android/SystemBars.java').decode('utf-8-sig')
 previous=run(old)
 assert previous.returncode!=0 and 'NullPointerException' in previous.stderr,previous.stderr
 print('Confirmed: 0.7.0 reproduces NullPointerException with an uninitialized window on API 30.')
current=run((root/'app/src/main/java/cc/echomusic/android/SystemBars.java').read_text(encoding='utf-8-sig'))
print(current.stdout.strip())
if current.returncode:print(current.stderr);sys.exit(current.returncode)
