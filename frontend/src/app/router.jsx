import { createBrowserRouter } from 'react-router-dom';
import App from './App';
import { DashboardPage } from '../pages/Dashboard';
import { GroupPage } from '../pages/Group';
import { MyPage } from '../pages/My';
import { LoginPage } from '../pages/Auth/LoginPage';
import { LandingPage } from '../pages/Landing';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <LandingPage></LandingPage>},
      { path: 'login', element: <LoginPage></LoginPage> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'group', element: <GroupPage /> },
      { path: 'my', element: <MyPage /> },
    ],
  },
]);
