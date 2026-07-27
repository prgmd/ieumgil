import { createBrowserRouter } from 'react-router-dom';

import App from './App';
import { LandingPage } from '../pages/Landing';
import { AuthPage } from '../pages/Auth';
import { DashboardPage } from '../pages/Dashboard';
import { GroupPage } from '../pages/Group';
import { MyPage } from '../pages/My';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      { index: true, element: <LandingPage /> },
      { path: 'auth', element: <AuthPage /> },
      { path: 'dashboard', element: <DashboardPage /> },
      { path: 'group', element: <GroupPage /> },
      { path: 'my', element: <MyPage /> },
    ],
  },
]);
