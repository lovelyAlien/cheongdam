// 앱(Capacitor)은 서버 주소를 빌드 때 주입한다. 웹은 비워 두면 같은 출처의 /api를 쓴다.
export const API_BASE: string = import.meta.env.VITE_API_BASE ?? ''
